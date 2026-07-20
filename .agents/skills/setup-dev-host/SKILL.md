---
name: Setup New Dev Host
description: Guidelines and instructions to bootstrap and configure a new development environment/host, including core tools (herdr, ralphex, revdiff, npm, claude, codex, agy, rg, gh, nvim, golang), yadm dotfiles, and Claude common skills.
---

# Setup New Dev Host Skill

Use this skill when bootstrapping or configuring a new development environment or host. This guide outlines the system setup, tool installation, Neovim customization, dotfile configuration, and Claude plugin management.

---

## Prerequisites & Base Tools Installation

Before starting, update the system package manager and install base packages.

### For Debian/Ubuntu (amd64):
```bash
sudo apt-get update
sudo apt-get install -y git curl gh nvim fzf ripgrep golang-go yadm build-essential
```

### For macOS:
```bash
brew update
brew install git gh neovim fzf ripgrep golang yadm
```

---

## 1. Install `herdr`, `ralphex`, and `revdiff`

These tools are installed as system binaries.

### On Debian/Ubuntu (amd64):
```bash
# Create a temporary installation directory
mkdir -p /tmp/install-dev-tools && cd /tmp/install-dev-tools

# Download latest stable herdr binary
gh release download v0.7.3 -R ogulcancelik/herdr -p "herdr-linux-x86_64"
sudo mv herdr-linux-x86_64 /usr/local/bin/herdr
sudo chmod +x /usr/local/bin/herdr

# Download latest stable ralphex deb package
gh release download v1.6.0 -R umputun/ralphex -p "ralphex_1.6.0_linux_amd64.deb"
# Download latest stable revdiff deb package
gh release download v1.11.0 -R umputun/revdiff -p "revdiff_1.11.0_linux_amd64.deb"

# Install deb packages
sudo dpkg -i ralphex_1.6.0_linux_amd64.deb revdiff_1.11.0_linux_amd64.deb

# Clean up
cd - && rm -rf /tmp/install-dev-tools
```

### On macOS (via Homebrew):
```bash
brew install ogulcancelik/tap/herdr
brew install umputun/apps/ralphex
brew install umputun/apps/revdiff
```

---

## 2. Node.js, npm, and Claude Code Setup

1. **Install Node.js & npm:**
   Use a manager like `fnm` or `nvm` (recommended):
   ```bash
   curl -fsSL https://fnm.vercel.sh/install | bash
   # Restart shell or source rc file
   fnm install --lts
   ```
2. **Install Claude Code CLI:**
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```

---

## 3. GitHub CLI & Credential Helper

Ensure credentials helper is configured so `yadm` and `git` authenticate seamlessly.

```bash
# Login (follow interactive prompt)
gh auth login

# Configure git helper
gh auth setup-git
```

---

## 4. Dotfiles Setup via `yadm`

Clone the dotfiles repository and check out target settings.

```bash
# Clone the dotfiles repository
yadm clone https://github.com/<your-username>/dotfiles.git

# Check out target configurations
yadm checkout .claude/settings.json
yadm checkout .gitconfig

# Restore git credentials setup-git if needed
gh auth setup-git
```

---

## 5. Neovim (`nvim`) Detailed Configuration

Follow these steps to clone and adapt the Neovim configuration to the host:

1. **Clone Config Repository:**
   ```bash
   gh repo clone <your-username>/nvim-dotfiles ~/.config/nvim
   ```
2. **Setup Local Configuration:**
   Copy the local/secrets template:
   ```bash
   cp ~/.config/nvim/local.vim.example ~/.config/nvim/local.vim
   ```
3. **Configure `fzf` Runtimepath Compatibility:**
   Your config might point to `/opt/homebrew/opt/fzf` by default. In `~/.config/nvim/init.vim`, configure it dynamically to support Linux (apt) installations as well:
   ```vim
   " fzf (installed via homebrew or apt, not vim-plug)
   if isdirectory('/opt/homebrew/opt/fzf')
     set rtp+=/opt/homebrew/opt/fzf
   elseif isdirectory('/usr/share/doc/fzf/examples')
     set rtp+=/usr/share/doc/fzf/examples
   endif
   ```
4. **Configure LSP Version Safety:**
   If your `init.vim` uses native LSP configuration syntax that requires Neovim 0.11+ (like `vim.lsp.config` and `vim.lsp.enable`), guard these calls to ensure Neovim launches cleanly without errors on older versions (like v0.9.5):
   ```lua
   if vim.lsp.config then
     vim.lsp.config('basedpyright', {
       cmd = { bp ~= '' and bp or 'basedpyright-langserver', '--stdio' },
       filetypes = { 'python' },
       root_markers = { 'pyproject.toml', 'uv.lock', 'poetry.lock', 'setup.py', 'setup.cfg', 'requirements.txt', '.git' },
       settings = {
         basedpyright = {
           analysis = { typeCheckingMode = 'standard', diagnosticMode = 'openFilesOnly' },
         },
       },
     })
     vim.lsp.enable('basedpyright')
   else
     -- Fallback warning for Neovim < 0.11
     print("LSP not configured: Neovim version is < 0.11 (native LSP config not supported)")
   end
   ```
5. **Headless Plugin Bootstrapping:**
   Run Neovim plugin installer (`vim-plug`) headlessly to fetch and install all active plugins:
   ```bash
   nvim --headless +PlugInstall +qa
   ```

---

## 6. Claude Plugins & Marketplaces

1. **Add Custom Marketplaces:**
   ```bash
   claude plugin marketplace add DietrichGebert/ponytail
   claude plugin marketplace add umputun/revdiff
   claude plugin marketplace add forrestchang/andrej-karpathy-skills
   claude plugin marketplace add umputun/cc-thingz
   claude plugin marketplace add umputun/ralphex
   ```
2. **Install & Enable Plugins:**
   ```bash
   claude plugin install ponytail@ponytail
   claude plugin install ralphex@ralphex
   claude plugin install revdiff-planning@revdiff
   claude plugin install revdiff@revdiff
   ```

---

## 7. Claude Common Skills Integration

Symlink your common skills repository directly to Claude's skills path for automatic updates.

```bash
# Clone skills repository
gh repo clone <your-username>/claude-common-skills ~/.claude/claude-common-skills

# Remove local skills folder and symlink the repository directory
rm -rf ~/.claude/skills
ln -sf ~/.claude/claude-common-skills/skills ~/.claude/skills
```
