# OxBreeze

A WebStorm 2026.3 plugin with Oxfmt formatting on save, live Oxlint diagnostics and quick fixes, and persistent language services for both tools.

## Usage

Install `oxfmt` and/or `oxlint`, or `vite-plus`, in your project. Install the plugin ZIP through **Settings → Plugins → Install Plugin from Disk**. Open a supported source file for live linting, and save a file to format it.

Explicit Save (Ctrl+S or File → Save All) also formats the active file when it has no unsaved changes. Autosave formats modified files.

Under **Settings → Tools → OxBreeze**, independently enable formatting and linting and supply executable overrides. The extension checkboxes control formatting only. Settings are local to the project; existing formatting preferences are preserved. All listed formats default to enabled except `.hbs`. Svelte and Astro still require their formatter dependencies and configuration.

OxBreeze searches ancestor `node_modules/.bin` directories. Projects declaring `vite-plus` use `vp fmt --lsp` and `vp lint --lsp`; other projects use `oxfmt --lsp` and `oxlint --lsp`. Relative executable overrides resolve against the IDE project root. Overrides named `vp`, `vp.cmd`, or `vp.exe` use Vite+ mode. Tools are discovered independently and are never downloaded by the plugin.

Linting starts for open JS/TS files (including JSX/TSX and module extensions), Vue, Svelte, and Astro. It uses WebStorm's native LSP support for unsaved edits, diagnostics, and Alt+Enter quick fixes. Framework-file linting follows Oxlint's script-block support. Project lint rules, ignore patterns, and type-aware configuration remain authoritative. Lint fixes are manual; saving does not automatically apply them.

Click the **OxBreeze icon in the bottom-right status bar** to view formatter and linter statuses, restart either service, **Restart both services**, or open Settings. Restarts affect all sessions in the current project, rediscover tools, and reconnect open lint files. Previously used formatter contexts are initialized without editing documents. Disabled services stay disabled; unused services remain idle. Missing lint tools appear in the status menu without repeated notifications. If the icon is hidden, enable **OxBreeze services** in the status-bar context menu.

Formatting uses one persistent `oxfmt --lsp` or `vp fmt --lsp` process per resolved executable and package directory. Startup and requests run on a background worker. A separate 10-second watchdog kills stalled processes, including their children; the next save restarts failed servers. Project disposal stops all servers. Results apply only if the document has not changed. Formatter errors leave the original file intact and show a notification. Disable competing format-on-save integrations for predictable results.

## Development

Use JDK 21 to run Gradle (the sandbox IDE uses its bundled runtime). Run the included Gradle wrapper:

```text
./gradlew test buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

For an installed WebStorm SDK, pass `-PlocalIdePath="/path/to/WebStorm"`. The default SDK is WebStorm 2026.3. The distributable is written to `build/distributions/`.

Set `OXBREEZE_TEST_VP` and/or `OXBREEZE_TEST_OXLINT` to installed executables to run the optional real-server tests. These verify diagnostics, quick fixes, and unsaved edits with push and pull diagnostics in temporary projects; Vite+ also exercises repeated formatting requests.

Manual checks in the sandbox IDE: open ReBreak, save `frontend/src` files, confirm Vite+ configuration is honored, verify `.hbs` is untouched, and edit a file repeatedly during formatting to check that newer edits survive. Also check invalid syntax, missing binaries, paths with spaces, and IDE autosave.

For linting, introduce and correct a lint violation without saving, apply an Alt+Enter fix, and confirm diagnostics update. Check standalone Oxlint and Vite+, independent nested packages, both settings toggles, each status-menu restart action, recovery after a server exits, and closing the project. Restart while formatting is in flight and confirm no old result overwrites the document. Disable other Oxlint integrations to avoid duplicate diagnostics.
