# Indecis Mobile Observer 1.0.0

Fabric 1.21.11 client-only telemetry sensor for the IndecisTracker system.

## What it logs

Only while connected to a configured 6b6t hostname:

- mobile session start/end
- heartbeat + observer health
- nearby player sightings and visual-range exits
- End Crystal spawn/disappear events
- explosion packets, including nearby-player context
- dimension and precise coordinates **for private backend correlation only**

It does **not** capture your typed chat, private messages, inventory contents, passwords, Microsoft tokens, or Discord credentials.

## First run

Install the JAR plus Fabric API, launch Minecraft once, then edit:

`config/indecis-observer.json`

Set:

```json
{
  "enabled": true,
  "endpointUrl": "http://YOUR_TAILSCALE_SERVER_IP:18882/v1/events",
  "apiToken": "TOKEN_FROM_THE_VPS_INSTALLER",
  "observerId": "mobile-01",
  "allowedServerSuffixes": ["6b6t.org"]
}
```

Restart Minecraft after editing the config.

## Privacy design

Precise mobile-observer coordinates are sent to the receiver's private event stream for correlation. The receiver creates a sanitized copy for the normal shared tracker stream so Discord/public-facing systems do not receive exact mobile-observer location.
