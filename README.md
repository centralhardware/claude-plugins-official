# centralhardware-plugins

Personal fork of [`anthropics/claude-plugins-official`](https://github.com/anthropics/claude-plugins-official),
pruned to the one plugin this marketplace actually serves: the **telegram**
channel plugin in `external_plugins/telegram`, rewritten in Kotlin — see
[`external_plugins/telegram/SERVER.md`](external_plugins/telegram/SERVER.md).

`.claude-plugin/marketplace.json` lists telegram only.

## Installation

```sh
claude plugin marketplace add centralhardware/claude-plugins-official
claude plugin install telegram@centralhardware-plugins
```

## Updating

```sh
claude plugin marketplace update centralhardware-plugins
claude plugin update telegram@centralhardware-plugins
```

## License

See [`LICENSE`](LICENSE) and the plugin's own `external_plugins/telegram/LICENSE`.
