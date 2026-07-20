# Create Go Web Project - Examples

This file contains real-world examples and patterns to use as reference when scaffolding new projects.

---

## Project Structure Examples

### Example 1: Full Stack Go + React + SQLite + R2 + Litestream + Google OAuth
**Pattern: Full-featured web application with auth and cloud storage**

**Project Structure:**
```
myapp/
├── cmd/
│   └── server/main.go
├── internal/
│   ├── api/           # HTTP handlers, routing
│   ├── auth/          # Google OAuth2 implementation
│   ├── db/            # Database connection (SQLite)
│   ├── middleware/    # CORS, rate limiting, logging
│   ├── migrations/    # Schema migrations
│   ├── repository/    # sqlc-generated data access layer
│   ├── service/       # Business logic
│   └── storage/       # R2 client for uploads
├── sql/
│   ├── migrations/
│   └── queries/       # sqlc query files
├── frontend/          # React + Vite + TypeScript
├── .env.example
├── .dockerignore
├── Dockerfile
├── docker-compose.yml
├── .github/workflows/
│   ├── deploy.yml
│   └── dev-deploy.yml
├── sqlc.yaml
├── start.sh
├── README.md
└── agents.md
```

---

### Example 2: Go + Vanilla HTML/JS + SQLite
**Pattern: Simple web application with static files**

**Project Structure:**
```
myapp/
├── cmd/main.go
├── internal/
│   └── (handlers, db, etc.)
├── web/
│   ├── index.html
│   ├── privacy.html
│   ├── favicon.svg
│   └── public/
│       └── style.css
├── .env.example
├── Dockerfile
├── docker-compose.yml
└── go.mod
```

---

### Example 3: Go + Telegram Bot + Web + SQLite + Litestream
**Pattern: Telegram bot with web dashboard**

**Project Structure:**
```
myapp/
├── cmd/
│   ├── bot/main.go
│   ├── server/main.go
│   └── importer/main.go
├── internal/
│   ├── bot/           # Telegram bot handlers
│   ├── server/        # Web API handlers
│   └── store/         # Data storage (SQLite)
├── web/
│   └── static/        # Static files (index.html, etc.)
├── .env.example
├── Dockerfile
├── docker-compose.yml
└── go.mod
```

---

## Authentication Patterns

### Pattern: Telegram WebApp initData Validation

Validates Telegram WebApp initData using HMAC-SHA256 signature verification.

```go
package auth

import (
    "context"
    "crypto/hmac"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "net/http"
    "net/url"
    "sort"
    "strconv"
    "strings"
    "time"
)

type ctxKey string

const UserCtxKey ctxKey = "user"

type TelegramUser struct {
    ID        int64  `json:"id"`
    FirstName string `json:"first_name"`
    LastName  string `json:"last_name"`
    Username  string `json:"username"`
}

// ValidateWebAppData validates Telegram WebApp initData
// See: https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
func ValidateWebAppData(botToken, initData string) (bool, *TelegramUser, error) {
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

    // HMAC-SHA256 signature
    secretKey := hmac.New(sha256.New, []byte("WebAppData"))
    secretKey.Write([]byte(botToken))
    secret := secretKey.Sum(nil)

    h := hmac.New(sha256.New, secret)
    h.Write([]byte(dataCheckString))
    calculatedHash := hex.EncodeToString(h.Sum(nil))

    if calculatedHash != hash {
        return false, nil, fmt.Errorf("hash mismatch")
    }

    // Check auth_date (24 hour expiry)
    authDateStr := parsed.Get("auth_date")
    if authDateStr == "" {
        return false, nil, fmt.Errorf("auth_date missing")
    }

    authDate, err := strconv.ParseInt(authDateStr, 10, 64)
    if err != nil {
        return false, nil, fmt.Errorf("invalid auth_date")
    }

    if time.Now().Unix()-authDate > 86400 {
        return false, nil, fmt.Errorf("auth_date expired")
    }

    // Parse user data
    userJSON := parsed.Get("user")
    var user TelegramUser
    if err := json.Unmarshal([]byte(userJSON), &user); err != nil {
        return true, nil, err
    }

    return true, &user, nil
}

// TelegramAuthMiddleware validates Telegram initData from header or query param
func TelegramAuthMiddleware(botToken string) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            initData := r.Header.Get("X-Telegram-Init-Data")
            if initData == "" {
                initData = r.URL.Query().Get("initData")
            }

            if initData == "" {
                http.Error(w, "Unauthorized: No init data", http.StatusUnauthorized)
                return
            }

            valid, user, err := ValidateWebAppData(botToken, initData)
            if !valid || err != nil {
                http.Error(w, "Unauthorized: Invalid hash", http.StatusForbidden)
                return
            }

            ctx := context.WithValue(r.Context(), UserCtxKey, user)
            next.ServeHTTP(w, r.WithContext(ctx))
        })
    }
}
```

---

### Pattern: Google OAuth2 with Admin Whitelist

Full Google OAuth2 implementation with session management and admin whitelist.

```go
package auth

import (
    "context"
    "encoding/base64"
    "encoding/json"
    "net/http"
    "os"
    "strings"
    "time"

    "golang.org/x/oauth2"
    "golang.org/x/oauth2/google"
)

// Config holds the authentication configuration
type Config struct {
    ClientID     string
    ClientSecret string
    RedirectURL  string
    AdminEmails  []string
    CookieName   string
    CookieSecret []byte
}

// GoogleOAuth2 holds the OAuth2 configuration and state
type GoogleOAuth2 struct {
    config       *oauth2.Config
    adminEmails  map[string]bool
    cookieName   string
    cookieSecret []byte
}

// UserInfo represents the authenticated user information
type UserInfo struct {
    Email   string
    Name    string
    Picture string
    IsAdmin bool
}

// NewGoogleOAuth2 creates a new Google OAuth2 instance
func NewGoogleOAuth2(cfg Config) *GoogleOAuth2 {
    if cfg.ClientID == "" || cfg.ClientSecret == "" {
        // Return a mock auth that allows all (for development)
        return &GoogleOAuth2{
            config:       nil,
            adminEmails:  map[string]bool{"dev@example.com": true},
            cookieName:   cfg.CookieName,
            cookieSecret: cfg.CookieSecret,
        }
    }

    // Parse admin emails into a map
    adminMap := make(map[string]bool)
    for _, email := range cfg.AdminEmails {
        trimmed := strings.TrimSpace(email)
        if trimmed != "" {
            adminMap[trimmed] = true
        }
    }

    oauth2Config := &oauth2.Config{
        ClientID:     cfg.ClientID,
        ClientSecret: cfg.ClientSecret,
        RedirectURL:  cfg.RedirectURL,
        Scopes: []string{
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
        },
        Endpoint: google.Endpoint,
    }

    return &GoogleOAuth2{
        config:       oauth2Config,
        adminEmails:  adminMap,
        cookieName:   cfg.CookieName,
        cookieSecret: cfg.CookieSecret,
    }
}

// GetLoginURL returns the Google OAuth2 login URL
func (g *GoogleOAuth2) GetLoginURL(state string) string {
    if g.config == nil {
        return "/auth/mock-login?state=" + state
    }
    return g.config.AuthCodeURL(state)
}

// ExchangeCode exchanges the authorization code for tokens
func (g *GoogleOAuth2) ExchangeCode(ctx context.Context, code string) (*oauth2.Token, error) {
    if g.config == nil {
        return &oauth2.Token{
            AccessToken: "mock-token",
            Expiry:      time.Now().Add(time.Hour * 24),
        }, nil
    }
    return g.config.Exchange(ctx, code)
}

// GetUserInfo retrieves user information from Google
func (g *GoogleOAuth2) GetUserInfo(ctx context.Context, token *oauth2.Token) (*UserInfo, error) {
    if g.config == nil || token == nil {
        return &UserInfo{
            Email:   "dev@example.com",
            Name:    "Dev User",
            Picture: "https://example.com/avatar.png",
            IsAdmin: true,
        }, nil
    }

    client := g.config.Client(ctx, token)
    resp, err := client.Get("https://www.googleapis.com/oauth2/v2/userinfo")
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()

    var googleUser struct {
        Email   string `json:"email"`
        Name    string `json:"name"`
        Picture string `json:"picture"`
    }

    if err := json.NewDecoder(resp.Body).Decode(&googleUser); err != nil {
        return nil, err
    }

    return &UserInfo{
        Email:   googleUser.Email,
        Name:    googleUser.Name,
        Picture: googleUser.Picture,
        IsAdmin: g.adminEmails[googleUser.Email],
    }, nil
}

// IsAdmin checks if the email is in the admin whitelist
func (g *GoogleOAuth2) IsAdmin(email string) bool {
    return g.adminEmails[email]
}

// SetSessionCookie sets a secure session cookie
func (g *GoogleOAuth2) SetSessionCookie(w http.ResponseWriter, user *UserInfo) {
    cookie := &http.Cookie{
        Name:     g.cookieName,
        Value:    encodeUserInfo(user),
        Path:     "/",
        HttpOnly: true,
        Secure:   os.Getenv("GO_ENV") == "production",
        SameSite: http.SameSiteLaxMode,
        MaxAge:   60 * 60 * 24 * 7, // 7 days
    }
    http.SetCookie(w, cookie)
}

// ClearSessionCookie clears the session cookie
func (g *GoogleOAuth2) ClearSessionCookie(w http.ResponseWriter) {
    cookie := &http.Cookie{
        Name:     g.cookieName,
        Value:    "",
        Path:     "/",
        HttpOnly: true,
        MaxAge:   -1,
    }
    http.SetCookie(w, cookie)
}

// RequireAdmin is a middleware that checks for valid admin session
func (g *GoogleOAuth2) RequireAdmin(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        user, err := g.GetSessionFromCookie(r)
        if err != nil || user == nil {
            http.Redirect(w, r, "/login", http.StatusFound)
            return
        }

        if !user.IsAdmin {
            http.Error(w, "Forbidden: Admin access required", http.StatusForbidden)
            return
        }

        ctx := context.WithValue(r.Context(), UserContextKey, user)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}

type ContextKey string

const UserContextKey ContextKey = "user"

func encodeUserInfo(user *UserInfo) string {
    data := user.Email + "|" + user.Name + "|" + user.Picture + "|" + boolToStr(user.IsAdmin)
    return base64.URLEncoding.EncodeToString([]byte(data))
}

func boolToStr(b bool) string {
    if b {
        return "1"
    }
    return "0"
}
```

---

## Server Patterns

### Pattern: Graceful Shutdown

Handles SIGINT/SIGTERM signals and gracefully shuts down the HTTP server.

```go
package main

import (
    "context"
    "log/slog"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"
)

func main() {
    // Initialize logger
    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
    slog.SetDefault(logger)

    // Create router
    mux := http.NewServeMux()
    mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        w.Write([]byte(`{"status":"ok"}`))
    })

    // Configure server
    addr := os.Getenv("SERVER_ADDR")
    if addr == "" {
        addr = ":8080"
    }

    srv := &http.Server{
        Addr:         addr,
        Handler:      mux,
        ReadTimeout:  30 * time.Second,
        WriteTimeout: 30 * time.Second,
        IdleTimeout:  60 * time.Second,
    }

    // Start server in goroutine
    go func() {
        slog.Info("server starting", "addr", addr)
        if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            slog.Error("server failed", "error", err)
            os.Exit(1)
        }
    }()

    // Wait for interrupt signal
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit
    slog.Info("shutting down server...")

    // Graceful shutdown with 30s timeout
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    if err := srv.Shutdown(ctx); err != nil {
        slog.Error("server forced to shutdown", "error", err)
        os.Exit(1)
    }

    slog.Info("server exited gracefully")
}
```

---

### Pattern: Healthcheck Endpoint

Standard health check endpoint returning JSON status.

```go
package api

import (
    "encoding/json"
    "net/http"
)

type HealthResponse struct {
    Status  string `json:"status"`
    Version string `json:"version,omitempty"`
}

func (h *Handler) Health(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(HealthResponse{
        Status:  "ok",
        Version: h.version,
    })
}
```

---

## Middleware Patterns

### Pattern: CORS Middleware

Configurable CORS middleware for cross-origin requests.

```go
package middleware

import (
    "net/http"
    "strings"
)

type CORSConfig struct {
    AllowedOrigins []string
    AllowedMethods []string
    AllowedHeaders []string
    MaxAge         int
}

func DefaultCORSConfig() CORSConfig {
    return CORSConfig{
        AllowedOrigins: []string{"*"},
        AllowedMethods: []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
        AllowedHeaders: []string{"Content-Type", "Authorization", "X-Telegram-Init-Data"},
        MaxAge:         86400,
    }
}

func CORS(config CORSConfig) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            origin := r.Header.Get("Origin")
            
            // Check if origin is allowed
            allowed := false
            for _, o := range config.AllowedOrigins {
                if o == "*" || o == origin {
                    allowed = true
                    break
                }
            }

            if allowed {
                w.Header().Set("Access-Control-Allow-Origin", origin)
                w.Header().Set("Access-Control-Allow-Methods", strings.Join(config.AllowedMethods, ", "))
                w.Header().Set("Access-Control-Allow-Headers", strings.Join(config.AllowedHeaders, ", "))
                w.Header().Set("Access-Control-Max-Age", fmt.Sprintf("%d", config.MaxAge))
            }

            // Handle preflight
            if r.Method == "OPTIONS" {
                w.WriteHeader(http.StatusNoContent)
                return
            }

            next.ServeHTTP(w, r)
        })
    }
}
```

---

### Pattern: Rate Limiting Middleware

IP-based rate limiting middleware.

```go
package middleware

import (
    "net/http"
    "sync"
    "time"
)

type RateLimiter struct {
    visitors map[string]*visitor
    mu       sync.RWMutex
    rate     time.Duration
    limit    int
}

type visitor struct {
    lastSeen time.Time
    count    int
}

func NewRateLimiter(rate time.Duration, limit int) *RateLimiter {
    rl := &RateLimiter{
        visitors: make(map[string]*visitor),
        rate:     rate,
        limit:    limit,
    }
    // Cleanup old entries periodically
    go rl.cleanup()
    return rl
}

func (rl *RateLimiter) cleanup() {
    for {
        time.Sleep(time.Minute)
        rl.mu.Lock()
        for ip, v := range rl.visitors {
            if time.Since(v.lastSeen) > rl.rate*2 {
                delete(rl.visitors, ip)
            }
        }
        rl.mu.Unlock()
    }
}

func (rl *RateLimiter) Limit(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        ip := r.RemoteAddr
        
        rl.mu.Lock()
        v, exists := rl.visitors[ip]
        if !exists {
            rl.visitors[ip] = &visitor{time.Now(), 1}
            rl.mu.Unlock()
            next.ServeHTTP(w, r)
            return
        }

        if time.Since(v.lastSeen) > rl.rate {
            v.count = 1
            v.lastSeen = time.Now()
        } else {
            v.count++
        }

        if v.count > rl.limit {
            rl.mu.Unlock()
            http.Error(w, "Too Many Requests", http.StatusTooManyRequests)
            return
        }
        rl.mu.Unlock()
        next.ServeHTTP(w, r)
    })
}
```

---

### Pattern: Logging Middleware with slog

Request logging middleware using structured logging.

```go
package middleware

import (
    "log/slog"
    "net/http"
    "time"
)

type responseWriter struct {
    http.ResponseWriter
    status int
}

func (rw *responseWriter) WriteHeader(code int) {
    rw.status = code
    rw.ResponseWriter.WriteHeader(code)
}

func Logging(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        start := time.Now()
        rw := &responseWriter{ResponseWriter: w, status: http.StatusOK}
        
        next.ServeHTTP(rw, r)
        
        slog.Info("request",
            "method", r.Method,
            "path", r.URL.Path,
            "status", rw.status,
            "duration", time.Since(start),
            "ip", r.RemoteAddr,
        )
    })
}

func Recovery(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if err := recover(); err != nil {
                slog.Error("panic recovered", "error", err, "path", r.URL.Path)
                http.Error(w, "Internal Server Error", http.StatusInternalServerError)
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

---

## Database Patterns

### Pattern: Goose Migrations with Embedded SQL

Uses `github.com/pressly/goose/v3` with `//go:embed` to bundle migrations into the binary.

**internal/store/store.go** (or wherever your store lives):
```go
package store

import (
    "database/sql"
    "embed"
    "fmt"

    "github.com/pressly/goose/v3"
    _ "modernc.org/sqlite" // Pure Go SQLite driver
)

//go:embed migrations/*.sql
var embedMigrations embed.FS

type Store struct {
    db *sql.DB
}

func New(dbPath string) (*Store, error) {
    db, err := sql.Open("sqlite", dbPath)
    if err != nil {
        return nil, fmt.Errorf("failed to open database: %w", err)
    }

    if err := db.Ping(); err != nil {
        return nil, fmt.Errorf("failed to ping database: %w", err)
    }

    // Enable WAL mode for Litestream compatibility
    if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
        return nil, fmt.Errorf("failed to enable WAL mode: %w", err)
    }

    // Set goose dialect and base filesystem
    if err := goose.SetDialect("sqlite3"); err != nil {
        return nil, err
    }
    goose.SetBaseFS(embedMigrations)

    // Run migrations
    if err := goose.Up(db, "migrations"); err != nil {
        return nil, fmt.Errorf("failed to migrate db: %w", err)
    }

    return &Store{db: db}, nil
}

func (s *Store) Close() error {
    return s.db.Close()
}
```

**internal/store/migrations/001_init.sql:**
```sql
-- +goose Up
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    role TEXT DEFAULT 'user',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT DEFAULT 'pending',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_items_user ON items(user_id);

-- +goose Down
DROP TABLE IF EXISTS items;
DROP TABLE IF EXISTS users;
```

**internal/store/migrations/002_add_timestamps.sql:**
```sql
-- +goose Up
ALTER TABLE items ADD COLUMN updated_at DATETIME;

-- +goose Down
-- SQLite doesn't support DROP COLUMN easily, so this is a no-op
```

> **Note**: Goose uses `-- +goose Up` and `-- +goose Down` comments to separate up/down migrations. The `-- +goose Down` section is optional but recommended for rollback support.

---

### Pattern: sqlc Configuration

**sqlc.yaml** - pointing to migrations for schema:
```yaml
version: "2"
sql:
  - schema: "internal/migrations"
    queries: "sql/queries"
    engine: "sqlite"
    gen:
      go:
        package: "repository"
        out: "internal/repository"
        emit_json_tags: true
        emit_interface: true
```

---

### Pattern: sqlc Query Examples

Example sqlc queries demonstrating CRUD, joins, and aggregations.

**sql/queries/users.sql:**
```sql
-- name: CreateUser :one
INSERT INTO users (email, name, role, created_at)
VALUES (?, ?, ?, datetime('now'))
RETURNING *;

-- name: GetUserByID :one
SELECT * FROM users WHERE id = ?;

-- name: GetUserByEmail :one
SELECT * FROM users WHERE email = ?;

-- name: ListUsers :many
SELECT * FROM users ORDER BY created_at DESC;

-- name: UpdateUserRole :exec
UPDATE users SET role = ? WHERE id = ?;

-- name: DeleteUser :exec
DELETE FROM users WHERE id = ?;

-- name: UpsertUser :one
INSERT INTO users (email, name, role)
VALUES (?, ?, ?)
ON CONFLICT(email) DO UPDATE SET
    name = excluded.name,
    role = excluded.role
RETURNING *;
```

**sql/queries/items.sql:**
```sql
-- name: CreateItem :one
INSERT INTO items (user_id, title, description, status)
VALUES (?, ?, ?, ?)
RETURNING *;

-- name: GetItemWithUser :one
SELECT 
    i.*,
    u.name as user_name,
    u.email as user_email
FROM items i
JOIN users u ON i.user_id = u.id
WHERE i.id = ?;

-- name: ListItemsByUser :many
SELECT * FROM items 
WHERE user_id = ? 
ORDER BY created_at DESC;

-- name: ListRecentItems :many
SELECT i.*, u.name as user_name
FROM items i
JOIN users u ON i.user_id = u.id
ORDER BY i.created_at DESC
LIMIT ?;

-- name: CountItemsByStatus :many
SELECT status, COUNT(*) as count
FROM items
GROUP BY status;

-- name: UpdateItemStatus :one
UPDATE items SET status = ? WHERE id = ?
RETURNING *;
```

---

### Pattern: SQLite Database Setup with WAL Mode (Pure Go)

Uses `modernc.org/sqlite` - a pure Go SQLite implementation (no CGO required).

```go
package db

import (
    "database/sql"
    "fmt"
    "os"
    "path/filepath"

    _ "modernc.org/sqlite"
)

func NewDB(dbPath string) (*sql.DB, error) {
    // Ensure directory exists
    if err := os.MkdirAll(filepath.Dir(dbPath), 0755); err != nil {
        return nil, fmt.Errorf("create db directory: %w", err)
    }

    // modernc.org/sqlite uses "sqlite" as driver name
    db, err := sql.Open("sqlite", dbPath+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
    if err != nil {
        return nil, fmt.Errorf("open database: %w", err)
    }

    // Enable foreign keys
    if _, err := db.Exec("PRAGMA foreign_keys = ON"); err != nil {
        return nil, fmt.Errorf("enable foreign keys: %w", err)
    }

    // Verify WAL mode
    var journalMode string
    if err := db.QueryRow("PRAGMA journal_mode").Scan(&journalMode); err != nil {
        return nil, fmt.Errorf("check journal mode: %w", err)
    }

    if journalMode != "wal" {
        return nil, fmt.Errorf("expected WAL mode, got %s", journalMode)
    }

    return db, nil
}
```

---

## Storage Patterns

### Pattern: R2/S3 Storage Client

Cloudflare R2 storage client using AWS SDK v2.

```go
package storage

import (
    "context"
    "fmt"
    "io"

    "github.com/aws/aws-sdk-go-v2/aws"
    "github.com/aws/aws-sdk-go-v2/config"
    "github.com/aws/aws-sdk-go-v2/credentials"
    "github.com/aws/aws-sdk-go-v2/service/s3"
)

type R2Config struct {
    AccessKeyID     string
    SecretAccessKey string
    AccountID       string
    BucketName      string
    PublicDomain    string
}

type R2Storage struct {
    client      *s3.Client
    bucket      string
    publicURL   string
}

func NewR2Storage(cfg R2Config) (*R2Storage, error) {
    if cfg.AccessKeyID == "" || cfg.SecretAccessKey == "" {
        return nil, fmt.Errorf("R2 credentials not configured")
    }

    endpoint := fmt.Sprintf("https://%s.r2.cloudflarestorage.com", cfg.AccountID)

    awsCfg, err := config.LoadDefaultConfig(context.TODO(),
        config.WithCredentialsProvider(
            credentials.NewStaticCredentialsProvider(cfg.AccessKeyID, cfg.SecretAccessKey, ""),
        ),
        config.WithRegion("auto"),
    )
    if err != nil {
        return nil, err
    }

    client := s3.NewFromConfig(awsCfg, func(o *s3.Options) {
        o.BaseEndpoint = aws.String(endpoint)
    })

    return &R2Storage{
        client:    client,
        bucket:    cfg.BucketName,
        publicURL: cfg.PublicDomain,
    }, nil
}

func (s *R2Storage) Upload(ctx context.Context, key string, body io.Reader, contentType string) error {
    _, err := s.client.PutObject(ctx, &s3.PutObjectInput{
        Bucket:      aws.String(s.bucket),
        Key:         aws.String(key),
        Body:        body,
        ContentType: aws.String(contentType),
    })
    return err
}

func (s *R2Storage) GetURL(key string) string {
    return fmt.Sprintf("%s/%s", s.publicURL, key)
}

func (s *R2Storage) Delete(ctx context.Context, key string) error {
    _, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{
        Bucket: aws.String(s.bucket),
        Key:    aws.String(key),
    })
    return err
}
```

---

## Configuration Templates

### .env.example Template

```bash
# Database
DB_PATH=data/myapp.db

# Server
SERVER_ADDR=:8080
FRONTEND_PATH=frontend/dist

{{#if OAUTH_GOOGLE}}
# Google OAuth2
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URL=http://localhost:8080/auth/google/callback
ADMIN_EMAILS=
COOKIE_SECRET=
{{/if}}

{{#if TELEGRAM}}
# Telegram
TG_BOT_TOKEN=
{{/if}}

{{#if CORS}}
# CORS
CORS_ORIGINS=*
{{/if}}

{{#if R2}}
# Cloudflare R2 Storage
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_ACCOUNT_ID=
R2_BUCKET_NAME=
R2_PUBLIC_DOMAIN=
{{/if}}

{{#if LITESTREAM}}
# Litestream (separate R2 credentials!)
LITESTREAM_R2_ACCESS_KEY_ID=
LITESTREAM_R2_SECRET_ACCESS_KEY=
LITESTREAM_R2_ACCOUNT_ID=
LITESTREAM_R2_BUCKET_NAME=
{{/if}}

# Traefik Network
NETWORK_NAME=traefik_network
HOSTNAME=myapp.example.com
```

---

## Documentation Templates

### agents.md Template

AI assistant guide for understanding and working with the project.

```markdown
# Project Guide for AI Assistants

## Overview

This is a Go web application with [describe stack].

## Technical Stack
- **Language:** Go (Golang)
- **Database:** SQLite (WAL mode enabled)
- **Frontend:** [React + Vite / Vanilla HTML/JS]
- **Authentication:** [Google OAuth2 / Telegram WebApp Auth]
- **Object Storage:** Cloudflare R2 (S3-compatible)

## Key Directories

| Directory | Purpose |
|-----------|---------|
| `cmd/server/` | Application entry point |
| `internal/api/` | HTTP handlers and routes |
| `internal/auth/` | Authentication (OAuth, sessions) |
| `internal/middleware/` | CORS, rate limiting, logging |
| `internal/repository/` | sqlc-generated database layer |
| `internal/service/` | Business logic |
| `internal/storage/` | R2/S3 file uploads |
| `sql/migrations/` | Database schema migrations |
| `sql/queries/` | sqlc query definitions |
| `web/` or `frontend/` | Static/built frontend files |

## Common Tasks

### Adding a New API Endpoint

1. Add handler function in `internal/api/handlers.go`
2. Register route in `internal/api/router.go`
3. If database access needed:
   - Add query in `sql/queries/`
   - Run `sqlc generate`
   - Use generated methods in handler

### Modifying Database Schema

1. Create new migration file in `sql/migrations/` (sequential numbering)
2. Update/add queries in `sql/queries/`
3. Run `sqlc generate`
4. Restart application (migrations run on startup)

### Environment Variables

Configuration is via environment variables. Copy `.env.example` to `.env` for local development.

### Running Locally

```bash
# Copy environment
cp .env.example .env

# Start dev server
./start.sh

# Or run directly
go run ./cmd/server
```

### Building

```bash
# Build binary
go build -o server ./cmd/server

# Build with vendoring
go build -mod vendor -o server ./cmd/server

# Docker build
docker build -t myapp .
```

### Viewing Logs

```bash
docker-compose logs -f myapp
```
```

---

## File Templates

### .gitignore

```
# Dependencies
/node_modules
/vendor

# Build outputs
/frontend/dist
/frontend/build
/server
/main

# IDE
.idea/
.vscode/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Environment
.env
.env.local
!.env.example

# Database
*.db
*.db-wal
*.db-shm
data/

# Logs
*.log
```

### README.md Template

```markdown
# Project Name

Brief description of the project.

## Prerequisites

- Go 1.25+
- Docker & Docker Compose
- Node.js 22+ (if using frontend)

## Quick Start

```bash
# Clone repository
git clone https://github.com/owner/project.git
cd project

# Copy environment
cp .env.example .env
# Edit .env with your values

# Run locally
./start.sh

# Or with Docker
docker-compose up -d
```

## Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `DB_PATH` | SQLite database path | No (default: `data/app.db`) |
| `SERVER_ADDR` | Server listen address | No (default: `:8080`) |
| `GOOGLE_CLIENT_ID` | Google OAuth client ID | If using Google auth |
| `TG_BOT_TOKEN` | Telegram bot token | If using Telegram |

## Deployment

1. Push to GitHub
2. CI builds and pushes image to GHCR
3. Portainer automatically deploys via webhook

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| GET | `/api/...` | API routes |

## License

MIT
```
