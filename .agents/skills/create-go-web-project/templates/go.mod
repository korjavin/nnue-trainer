module {{PROJECT_NAME}}

go 1.25

require (
    github.com/joho/godotenv v1.5.1
    github.com/pressly/goose/v3 v3.24.1
    modernc.org/sqlite v1.37.0
)

{{#if R2}}
require (
    github.com/aws/aws-sdk-go-v2 v1.41.0
    github.com/aws/aws-sdk-go-v2/config v1.32.6
    github.com/aws/aws-sdk-go-v2/credentials v1.19.6
    github.com/aws/aws-sdk-go-v2/service/s3 v1.95.0
)
{{/if}}

{{#if OAUTH_GOOGLE}}
require golang.org/x/oauth2 v0.34.0
{{/if}}

{{#if TELEGRAM}}
require github.com/telebot/tele v1.0.0
{{/if}}

{{#if WEBSOCKET}}
require github.com/gorilla/websocket v1.5.1
{{/if}}
