# Telegram Web Login Skill

This skill helps add Telegram Login Widget authentication to web applications, enabling users to log in via their Telegram account.

## When to Use This Skill

Use this skill when the user wants to:
- Add Telegram authentication to their web app
- Allow browser users to log in via Telegram
- Protect API endpoints with authentication
- Replace or complement Google/GitHub OAuth

## Prerequisites

1. **Telegram Bot** - Created via [@BotFather](https://t.me/botfather)
2. **Domain Linked** - Use `/setdomain` command in BotFather
3. **HTTPS** - Telegram Login Widget requires HTTPS

## Key Concepts

### Telegram Login Widget vs WebApp Data

There are TWO different Telegram auth mechanisms with DIFFERENT validation:

| Feature | Telegram Login Widget | Telegram WebApp (Mini App) |
|---------|----------------------|---------------------------|
| Context | Browser (standalone site) | Inside Telegram app |
| Data | JSON object via callback | URL-encoded initData |
| Secret | `SHA256(bot_token)` | `HMAC-SHA256("WebAppData", bot_token)` |
| Fields | id, first_name, auth_date, hash | user JSON, auth_date, hash |

**CRITICAL**: These use different signature algorithms!

### Security Model

1. **Signature Validation** - HMAC-SHA256 prevents data tampering
2. **User ID Allowlist** - Restrict access to specific Telegram user IDs
3. **Session Cookies** - HttpOnly cookies for subsequent requests
4. **Auth Middleware** - Protect all API routes

## Implementation Process

### 1. Analyze Project Structure

Determine:
- Is this a Telegram Mini App, standalone web app, or both?
- What web framework is used (Go stdlib, Gin, Echo, etc.)?
- Is there existing authentication?
- Where to store session data?

### 2. Get Bot Username Dynamically

**Never hardcode the bot username!** Get it from the Telegram API:

```go
// In bot initialization
func (b *Bot) Username() string {
    return b.api.Self.UserName  // From tgbotapi
}

// Pass to server
botUsername := tgBot.Username()
srv := server.New(store, botToken, allowedUserID, botUsername)
```

### 3. Inject Bot Username into HTML

Inject at serve time to avoid public API endpoints:

```go
func (s *Server) serveIndex(w http.ResponseWriter, r *http.Request) {
    content, _ := os.ReadFile("./web/static/index.html")
    html := strings.ReplaceAll(string(content), "BOT_USERNAME_PLACEHOLDER", s.botUsername)
    w.Write([]byte(html))
}
```

In HTML:
```html
<script>window.BOT_USERNAME = "BOT_USERNAME_PLACEHOLDER";</script>
```

### 4. Add Telegram Login Widget (Frontend)

```javascript
// Create widget dynamically
const tgScript = document.createElement('script');
tgScript.src = "https://telegram.org/js/telegram-widget.js?22";
tgScript.setAttribute('data-telegram-login', window.BOT_USERNAME);
tgScript.setAttribute('data-size', 'large');
tgScript.setAttribute('data-onauth', 'onTelegramAuth(user)');
tgScript.setAttribute('data-request-access', 'write');

document.body.appendChild(tgScript);

// Handle callback
window.onTelegramAuth = async function(user) {
    const res = await fetch('/auth/telegram/callback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(user)
    });
    if (res.ok) window.location.reload();
};
```

### 5. Implement Signature Validation (Backend)

**For Telegram Login Widget (browser users):**

```go
func ValidateTelegramLoginWidget(token string, data TelegramLoginData) (bool, *TelegramUser, error) {
    // Build data-check-string (sorted alphabetically, excluding hash)
    var parts []string
    parts = append(parts, fmt.Sprintf("auth_date=%d", data.AuthDate))
    if data.FirstName != "" {
        parts = append(parts, fmt.Sprintf("first_name=%s", data.FirstName))
    }
    parts = append(parts, fmt.Sprintf("id=%d", data.ID))
    // ... add other non-empty fields
    
    sort.Strings(parts)
    dataCheckString := strings.Join(parts, "\n")

    // Secret key = SHA256(bot_token)
    secretHash := sha256.Sum256([]byte(token))

    // HMAC-SHA256(data_check_string, secret_key)
    h := hmac.New(sha256.New, secretHash[:])
    h.Write([]byte(dataCheckString))
    calculatedHash := hex.EncodeToString(h.Sum(nil))

    if calculatedHash != data.Hash {
        return false, nil, fmt.Errorf("hash mismatch")
    }

    // Check auth_date not expired (24 hours)
    if time.Now().Unix() - data.AuthDate > 86400 {
        return false, nil, fmt.Errorf("auth_date expired")
    }

    return true, &TelegramUser{ID: data.ID, ...}, nil
}
```

**For Telegram WebApp (Mini App users):**

```go
func ValidateWebAppData(token, initData string) (bool, *TelegramUser, error) {
    // Parse URL-encoded data
    parsed, _ := url.ParseQuery(initData)
    hash := parsed.Get("hash")
    parsed.Del("hash")

    // Build check string
    var keys []string
    for k := range parsed { keys = append(keys, k) }
    sort.Strings(keys)
    
    var arr []string
    for _, k := range keys {
        arr = append(arr, fmt.Sprintf("%s=%s", k, parsed.Get(k)))
    }
    dataCheckString := strings.Join(arr, "\n")

    // Different secret: HMAC-SHA256("WebAppData", bot_token)
    secretKey := hmac.New(sha256.New, []byte("WebAppData"))
    secretKey.Write([]byte(token))
    secret := secretKey.Sum(nil)

    h := hmac.New(sha256.New, secret)
    h.Write([]byte(dataCheckString))
    calculatedHash := hex.EncodeToString(h.Sum(nil))

    // ... validate and parse user JSON
}
```

### 6. Create Auth Callback Handler

```go
func (s *Server) handleTelegramCallback(w http.ResponseWriter, r *http.Request) {
    var data TelegramLoginData
    json.NewDecoder(r.Body).Decode(&data)

    log.Printf("[TG-LOGIN] Attempt: user_id=%d username=%s", data.ID, data.Username)

    valid, user, err := ValidateTelegramLoginWidget(s.botToken, data)
    if !valid {
        log.Printf("[TG-LOGIN] Validation failed: %v", err)
        http.Error(w, "Invalid login data", http.StatusUnauthorized)
        return
    }

    // IMPORTANT: Check if user is allowed
    if user.ID != s.allowedUserID {
        log.Printf("[TG-LOGIN] Unauthorized user: %d", user.ID)
        http.Error(w, "Forbidden", http.StatusForbidden)
        return
    }

    // Create session cookie
    sessionValue := createSessionToken(user.Username, s.botToken)
    http.SetCookie(w, &http.Cookie{
        Name:     "auth_session",
        Value:    sessionValue,
        Expires:  time.Now().Add(30 * 24 * time.Hour),
        HttpOnly: true,
        Path:     "/",
    })

    log.Printf("[TG-LOGIN] Success: user_id=%d", user.ID)
    json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}
```

### 7. Create Auth Middleware

Protect all API routes:

```go
func AuthMiddleware(botToken string, allowedUserID int64) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            // 1. Check session cookie first
            cookie, err := r.Cookie("auth_session")
            if err == nil {
                if email, ok := verifySessionToken(cookie.Value, botToken); ok {
                    // Authenticated via cookie
                    next.ServeHTTP(w, r)
                    return
                }
                log.Printf("[AUTH] Invalid session cookie from %s", r.RemoteAddr)
            }

            // 2. Check Telegram WebApp initData (for Mini App users)
            initData := r.Header.Get("X-Telegram-Init-Data")
            if initData == "" {
                log.Printf("[AUTH] No auth data from %s", r.RemoteAddr)
                http.Error(w, "Unauthorized", http.StatusUnauthorized)
                return
            }

            valid, user, err := ValidateWebAppData(botToken, initData)
            if !valid {
                log.Printf("[AUTH] Invalid hash from %s: %v", r.RemoteAddr, err)
                http.Error(w, "Unauthorized", http.StatusForbidden)
                return
            }

            if user.ID != allowedUserID {
                log.Printf("[AUTH] Unauthorized user %d from %s", user.ID, r.RemoteAddr)
                http.Error(w, "Forbidden", http.StatusForbidden)
                return
            }

            next.ServeHTTP(w, r)
        })
    }
}
```

### 8. Wire Up Routes

```go
func (s *Server) Routes() http.Handler {
    mux := http.NewServeMux()

    // Serve index with bot username injection
    mux.HandleFunc("/", s.serveIndex)

    // Auth routes (no middleware)
    mux.HandleFunc("/auth/telegram/callback", s.handleTelegramCallback)

    // Protected API routes
    apiMux := http.NewServeMux()
    apiMux.HandleFunc("GET /api/data", s.handleGetData)
    apiMux.HandleFunc("POST /api/data", s.handlePostData)

    // Apply auth middleware to all /api/* routes
    authMW := AuthMiddleware(s.botToken, s.allowedUserID)
    mux.Handle("/api/", authMW(apiMux))

    return mux
}
```

## Security Logging

Always log auth events for security monitoring:

```go
log.Printf("[TG-LOGIN] Attempt from %s: user_id=%d username=%s", r.RemoteAddr, data.ID, data.Username)
log.Printf("[TG-LOGIN] Success for user_id=%d from %s", user.ID, r.RemoteAddr)
log.Printf("[TG-LOGIN] Validation failed for user_id=%d: %v", data.ID, err)
log.Printf("[AUTH] Unauthorized user ID %d from %s", user.ID, r.RemoteAddr)
```

## BotFather Setup

Tell the user to configure their bot:

```
1. Open @BotFather on Telegram
2. Send /mybots and select your bot
3. Click "Bot Settings" → "Domain"
4. Send /setdomain and enter your domain (e.g., myapp.example.com)
5. Domain must match where Telegram Login Widget is hosted
```

## User Flow

```
Browser User Flow:
1. User opens web app in browser
2. App shows Telegram Login Widget
3. User clicks widget → Telegram auth popup
4. Telegram returns signed data to callback URL
5. Server validates signature + user ID
6. Server creates session cookie
7. User is logged in

Telegram Mini App Flow:
1. User opens bot in Telegram → clicks Menu button
2. Mini App opens with initData automatically
3. Frontend sends initData with each API request
4. Server validates signature + user ID
5. User is authenticated per-request
```

## Implementation Checklist

- [ ] Bot username obtained from `bot.api.Self.UserName`
- [ ] Bot username injected into HTML (not fetched via public API)
- [ ] Domain linked via BotFather `/setdomain`
- [ ] ValidateTelegramLoginWidget implemented (SHA256 secret)
- [ ] ValidateWebAppData implemented (HMAC "WebAppData" secret)
- [ ] User ID allowlist enforced
- [ ] Session cookies created with HttpOnly flag
- [ ] Auth middleware protects all /api/* routes
- [ ] Security logging for all auth events
- [ ] Frontend shows login widget when not authenticated
