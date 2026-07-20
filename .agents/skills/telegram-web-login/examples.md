# Telegram Web Login - Code Examples

Complete Go code examples from a working implementation.

## Data Structures

```go
// TelegramUser represents a Telegram user (used for both auth methods)
type TelegramUser struct {
    ID        int64  `json:"id"`
    FirstName string `json:"first_name"`
    LastName  string `json:"last_name"`
    Username  string `json:"username"`
}

// TelegramLoginData - data from Telegram Login Widget callback
type TelegramLoginData struct {
    ID        int64  `json:"id"`
    FirstName string `json:"first_name"`
    LastName  string `json:"last_name,omitempty"`
    Username  string `json:"username,omitempty"`
    PhotoURL  string `json:"photo_url,omitempty"`
    AuthDate  int64  `json:"auth_date"`
    Hash      string `json:"hash"`
}
```

## Validation Functions

### Telegram Login Widget Validation

For browser users logging in via the Telegram Login Widget:

```go
import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/hex"
    "fmt"
    "sort"
    "strings"
    "time"
)

// ValidateTelegramLoginWidget validates data from Telegram Login Widget
// Uses SHA256(bot_token) as secret key
func ValidateTelegramLoginWidget(token string, data TelegramLoginData) (bool, *TelegramUser, error) {
    // Build data-check-string: sorted fields joined with \n (excluding hash)
    var parts []string

    parts = append(parts, fmt.Sprintf("auth_date=%d", data.AuthDate))
    if data.FirstName != "" {
        parts = append(parts, fmt.Sprintf("first_name=%s", data.FirstName))
    }
    parts = append(parts, fmt.Sprintf("id=%d", data.ID))
    if data.LastName != "" {
        parts = append(parts, fmt.Sprintf("last_name=%s", data.LastName))
    }
    if data.PhotoURL != "" {
        parts = append(parts, fmt.Sprintf("photo_url=%s", data.PhotoURL))
    }
    if data.Username != "" {
        parts = append(parts, fmt.Sprintf("username=%s", data.Username))
    }

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

    // Check auth_date is not expired (24 hours)
    if time.Now().Unix()-data.AuthDate > 86400 {
        return false, nil, fmt.Errorf("auth_date expired")
    }

    user := &TelegramUser{
        ID:        data.ID,
        FirstName: data.FirstName,
        LastName:  data.LastName,
        Username:  data.Username,
    }

    return true, user, nil
}
```

### Telegram WebApp Validation

For users inside Telegram Mini App:

```go
import (
    "encoding/json"
    "net/url"
)

// ValidateWebAppData validates Telegram WebApp initData
// Uses HMAC-SHA256("WebAppData", bot_token) as secret key
func ValidateWebAppData(token, initData string) (bool, *TelegramUser, error) {
    if initData == "" {
        return false, nil, fmt.Errorf("empty init data")
    }

    parsed, err := url.ParseQuery(initData)
    if err != nil {
        return false, nil, err
    }

    hash := parsed.Get("hash")
    if hash == "" {
        return false, nil, fmt.Errorf("missing hash")
    }

    // Remove hash from map to build data check string
    parsed.Del("hash")

    var keys []string
    for k := range parsed {
        keys = append(keys, k)
    }
    sort.Strings(keys)

    var dataCheckArr []string
    for _, k := range keys {
        dataCheckArr = append(dataCheckArr, fmt.Sprintf("%s=%s", k, parsed.Get(k)))
    }
    dataCheckString := strings.Join(dataCheckArr, "\n")

    // DIFFERENT FROM LOGIN WIDGET: HMAC-SHA256 with "WebAppData" prefix
    secretKey := hmac.New(sha256.New, []byte("WebAppData"))
    secretKey.Write([]byte(token))
    secret := secretKey.Sum(nil)

    h := hmac.New(sha256.New, secret)
    h.Write([]byte(dataCheckString))
    calculatedHash := hex.EncodeToString(h.Sum(nil))

    if calculatedHash != hash {
        return false, nil, fmt.Errorf("hash mismatch")
    }

    // Check auth_date
    authDateStr := parsed.Get("auth_date")
    authDate, _ := strconv.ParseInt(authDateStr, 10, 64)
    if time.Now().Unix()-authDate > 86400 {
        return false, nil, fmt.Errorf("auth_date expired")
    }

    // Parse user JSON
    userJSON := parsed.Get("user")
    var user TelegramUser
    if err := json.Unmarshal([]byte(userJSON), &user); err != nil {
        return true, nil, err
    }

    return true, &user, nil
}
```

## Session Token Functions

```go
import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/base64"
    "encoding/hex"
    "strings"
)

func createSessionToken(username, secret string) string {
    h := hmac.New(sha256.New, []byte(secret))
    h.Write([]byte(username))
    sig := hex.EncodeToString(h.Sum(nil))
    return base64.URLEncoding.EncodeToString([]byte(username)) + "." + sig
}

func verifySessionToken(token, secret string) (string, bool) {
    parts := strings.Split(token, ".")
    if len(parts) != 2 {
        return "", false
    }

    usernameBytes, err := base64.URLEncoding.DecodeString(parts[0])
    if err != nil {
        return "", false
    }
    username := string(usernameBytes)

    h := hmac.New(sha256.New, []byte(secret))
    h.Write([]byte(username))
    expectedSig := hex.EncodeToString(h.Sum(nil))

    if parts[1] != expectedSig {
        return "", false
    }

    return username, true
}
```

## Auth Middleware

```go
import (
    "context"
    "log"
    "net/http"
)

type ctxKey string
const UserCtxKey ctxKey = "user"

func AuthMiddleware(botToken string, allowedUserID int64) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            // 1. Check session cookie first
            cookie, err := r.Cookie("auth_session")
            if err == nil {
                if username, ok := verifySessionToken(cookie.Value, botToken); ok {
                    user := &TelegramUser{
                        ID:       allowedUserID,
                        Username: username,
                    }
                    ctx := context.WithValue(r.Context(), UserCtxKey, user)
                    next.ServeHTTP(w, r.WithContext(ctx))
                    return
                }
                log.Printf("[AUTH] Invalid session cookie from %s", r.RemoteAddr)
            }

            // 2. Check Telegram WebApp initData
            initData := r.Header.Get("X-Telegram-Init-Data")
            if initData == "" {
                initData = r.URL.Query().Get("initData")
            }

            if initData == "" {
                log.Printf("[AUTH] No auth data from %s for %s %s", r.RemoteAddr, r.Method, r.URL.Path)
                http.Error(w, "Unauthorized: No init data", http.StatusUnauthorized)
                return
            }

            valid, user, err := ValidateWebAppData(botToken, initData)
            if !valid || err != nil {
                log.Printf("[AUTH] Invalid hash from %s: %v", r.RemoteAddr, err)
                http.Error(w, "Unauthorized: Invalid hash", http.StatusForbidden)
                return
            }

            if user.ID != allowedUserID {
                log.Printf("[AUTH] Unauthorized user ID %d (username: %s) from %s", user.ID, user.Username, r.RemoteAddr)
                http.Error(w, "Forbidden: User not allowed", http.StatusForbidden)
                return
            }

            ctx := context.WithValue(r.Context(), UserCtxKey, user)
            next.ServeHTTP(w, r.WithContext(ctx))
        })
    }
}
```

## Telegram Login Callback Handler

```go
func (s *Server) handleTelegramCallback(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost {
        http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
        return
    }

    var data TelegramLoginData
    if err := json.NewDecoder(r.Body).Decode(&data); err != nil {
        log.Printf("[TG-LOGIN] Invalid JSON from %s: %v", r.RemoteAddr, err)
        http.Error(w, "Invalid JSON", http.StatusBadRequest)
        return
    }

    log.Printf("[TG-LOGIN] Attempt from %s: user_id=%d username=%s auth_date=%d",
        r.RemoteAddr, data.ID, data.Username, data.AuthDate)

    valid, user, err := ValidateTelegramLoginWidget(s.botToken, data)
    if !valid || err != nil {
        log.Printf("[TG-LOGIN] Validation failed for user_id=%d: %v", data.ID, err)
        http.Error(w, "Invalid Telegram login data: "+err.Error(), http.StatusUnauthorized)
        return
    }

    if user.ID != s.allowedUserID {
        log.Printf("[TG-LOGIN] Unauthorized user_id=%d (allowed=%d) from %s",
            user.ID, s.allowedUserID, r.RemoteAddr)
        http.Error(w, "Forbidden: User not allowed", http.StatusForbidden)
        return
    }

    sessionValue := createSessionToken(user.Username, s.botToken)
    http.SetCookie(w, &http.Cookie{
        Name:     "auth_session",
        Value:    sessionValue,
        Expires:  time.Now().Add(30 * 24 * time.Hour),
        HttpOnly: true,
        Path:     "/",
    })

    log.Printf("[TG-LOGIN] Success for user_id=%d from %s", user.ID, r.RemoteAddr)
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}
```

## Bot Username Exposure

```go
// In bot package
type Bot struct {
    api           *tgbotapi.BotAPI
    store         *store.Store
    allowedUserID int64
}

// Username returns the bot's username from the Telegram API
func (b *Bot) Username() string {
    return b.api.Self.UserName
}

// In main.go
tgBot, _ := bot.New(botToken, allowedUserID, store)
botUsername := tgBot.Username()
log.Println("Bot username:", botUsername)

srv := server.New(store, botToken, allowedUserID, oidcConfig, botUsername)
```

## Serve Index with Bot Username Injection

```go
func (s *Server) serveIndexWithBotUsername(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")

    f, err := os.Open("./web/static/index.html")
    if err != nil {
        http.Error(w, "Internal Server Error", http.StatusInternalServerError)
        return
    }
    defer f.Close()

    content, _ := io.ReadAll(f)
    html := strings.ReplaceAll(string(content), "BOT_USERNAME_PLACEHOLDER", s.botUsername)

    w.Header().Set("Content-Type", "text/html; charset=utf-8")
    w.Write([]byte(html))
}
```

## Frontend JavaScript

```javascript
// Check if user needs to login
async function checkAuth() {
    // If inside Telegram WebApp, proceed normally
    const userInitData = window.Telegram.WebApp.initData;
    if (userInitData) {
        return true;
    }

    // Try existing session cookie
    try {
        const res = await fetch('/api/me', { method: 'GET' });
        if (res.status === 200) return true;
    } catch (e) {}

    // Show login options
    showLoginScreen();
    return false;
}

function showLoginScreen() {
    const container = document.createElement('div');
    container.style.cssText = "display:flex; flex-direction:column; align-items:center; justify-content:center; min-height:60vh; gap:20px;";

    // Telegram Login Widget
    const tgWidget = document.createElement('div');
    const tgScript = document.createElement('script');
    tgScript.src = "https://telegram.org/js/telegram-widget.js?22";
    tgScript.setAttribute('data-telegram-login', window.BOT_USERNAME);
    tgScript.setAttribute('data-size', 'large');
    tgScript.setAttribute('data-onauth', 'onTelegramAuth(user)');
    tgWidget.appendChild(tgScript);
    container.appendChild(tgWidget);

    document.body.innerHTML = "";
    document.body.appendChild(container);
}

// Telegram callback
window.onTelegramAuth = async function(user) {
    console.log("Telegram auth:", user);
    try {
        const res = await fetch('/auth/telegram/callback', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(user)
        });
        if (res.ok) {
            window.location.reload();
        } else {
            alert("Login failed: " + await res.text());
        }
    } catch (e) {
        alert("Login error: " + e.message);
    }
};
```

## HTML Template

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>My App</title>
    <script src="https://telegram.org/js/telegram-web-app.js"></script>
    <script>window.BOT_USERNAME = "BOT_USERNAME_PLACEHOLDER";</script>
</head>
<body>
    <div id="app">
        <!-- App content -->
    </div>
    <script src="static/js/app.js"></script>
</body>
</html>
```
