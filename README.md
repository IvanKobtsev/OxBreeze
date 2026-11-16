# OxBreeze

A minimal WebStorm 2026.3 plugin that formats local project files on save using a persistent Oxfmt formatting server. No linting or IDE analysis integration.

## Usage

Install `oxfmt` or `vite-plus` in your project, install the plugin ZIP through **Settings → Plugins → Install Plugin from Disk**, and save a file.

Under **Settings → Tools → OxBreeze**, enable or disable formatting, select file extensions, or supply an executable path. Settings are local to the project. All listed formats default to enabled except `.hbs`. Svelte and Astro still require their formatter dependencies and configuration.

OxBreeze searches ancestor `node_modules/.bin` directories. Projects declaring `vite-plus` use `vp fmt`; other projects use `oxfmt`. Relative executable overrides resolve against the IDE project root. Overrides named `vp`, `vp.cmd`, or `vp.exe` use Vite+ mode.

Formatting uses one persistent `oxfmt --lsp` or `vp fmt --lsp` process per resolved executable and package directory. Startup and requests run on a background worker. A separate 10-second watchdog kills stalled processes, including their children; the next save restarts failed servers. Project disposal stops all servers. Results apply only if the document has not changed. Formatter errors leave the original file intact and show a notification. Disable competing format-on-save integrations for predictable results.

## Development

Use JDK 21 or newer. Run the included Gradle wrapper:

```text
./gradlew test buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

For an installed WebStorm SDK, pass `-PlocalIdePath="/path/to/WebStorm"`. The default SDK is WebStorm 2026.3. The distributable is written to `build/distributions/`.

Manual checks in the sandbox IDE: open ReBreak, save `frontend/src` files, confirm Vite+ configuration is honored, verify `.hbs` is untouched, and edit a file repeatedly during formatting to check that newer edits survive. Also check invalid syntax, missing binaries, paths with spaces, and IDE autosave.
