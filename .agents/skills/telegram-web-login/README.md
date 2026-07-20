# Telegram Web Login Skill

Adds Telegram Login Widget authentication to web applications, allowing users to log in via their Telegram account.

## Features

- Telegram Login Widget integration for browser users
- HMAC-SHA256 signature validation (per Telegram specs)
- Session cookie-based authentication
- Auth middleware for API protection
- Comprehensive security logging

## When to Use

Use this skill when:
- Building a Telegram bot with a web interface
- Want to authenticate users via their Telegram account
- Need secure, single-user or multi-user web authentication
- Want to replace or complement OAuth providers (Google, GitHub, etc.)

## Files

- **SKILL.md** - Detailed implementation instructions
- **examples.md** - Complete Go code examples (auth, middleware, handlers)

## Quick Overview

1. Set up your Telegram bot via [@BotFather](https://t.me/botfather)
2. Link your domain with `/setdomain` command
3. Add auth validation (HMAC-SHA256 with SHA256(bot_token) as secret)
4. Create session cookies on successful login
5. Protect API routes with auth middleware

## Reference Implementation

This skill is based on patterns from a working Telegram bot with web interface.
