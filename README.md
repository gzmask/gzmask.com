# gzmask / archive

An experimental personal blog built with [Carp](https://github.com/carp-lang/Carp), [Datastar](https://data-star.dev/), and [`carpentry-org/web`](https://github.com/carpentry-org/web).

The index sends only post metadata. Selecting a title asks the Carp backend for that one article, then Datastar morphs the `#experience` region with a View Transition. Article images are local, lazy-loaded assets and are not requested until their article is opened.

## Architecture

```text
GET /
  -> complete HTML shell + title/date index only

Datastar GET /posts/:id
  -> one text/event-stream response
  -> datastar-patch-elements event
  -> morphs <title> and <main id="experience">

Direct GET /posts/:id
  -> complete server-rendered HTML document

GET /media/*
  -> locally archived post image, loaded lazily by the browser
```

This follows the Tao of Datastar where practical:

- The backend owns the post and navigation state.
- The server returns HTML rather than client-side templates or post JSON.
- A large, stable DOM region is morphed with the default `outer` strategy.
- Signals are used only for the loading indicator.
- Every article remains a real URL and works without JavaScript.

The enhanced navigation uses `history.pushState` so morphs remain addressable. This is a deliberate compromise with Datastar's recommendation to leave page history entirely to ordinary anchor navigation; the links remain progressively enhanced and work as normal links when JavaScript is unavailable.

## Requirements

- Current Carp `master` with `CARP_DIR` configured
- Clang
- Babashka, only when refreshing the Medium archive or running smoke tests

Local environment:

```sh
export CARP_DIR="$HOME/personal/Carp"
export PATH="$HOME/.local/bin:$PATH"
```

## Run

```sh
make run
```

Open <http://localhost:8080>.

To keep the server in a detached Zellij session:

```sh
make release
zellij attach --create-background persona-blog -- \
  /bin/zsh -lc 'cd ~/personal/hellow-carp && exec ./out/persona-blog'
```

Attach or stop it with:

```sh
zellij attach persona-blog
zellij kill-session persona-blog
```

## Test

```sh
make test
```

The smoke test verifies:

- The index contains all titles but no article bodies or media URLs
- All 82 direct article URLs render complete documents
- Datastar requests return correctly framed SSE patches
- All 27 recoverable article images are served locally
- Unknown post IDs return HTTP 404

## Import archived posts

```sh
make import
```

`scripts/import-archive.bb` reads the Medium RSS feed and the WordPress.com API for `gzmask`, then:

1. Imports the ten Medium posts and all non-duplicate WordPress posts (82 total).
2. Writes each article body to a separate `content/posts/:id.html` file.
3. Downloads every recoverable article image to `public/media/`.
4. Replaces images that have disappeared from their original hosts with an archive-unavailable marker.
5. Adds native lazy-loading attributes to images.
6. Generates `content/posts.json` for inspection.
7. Generates the Carp metadata module `posts.carp`.

Article bodies and images are intentionally absent from the initial page. The import is a build/content-management task; the production server does not depend on Medium or WordPress at request time.

## Build

```sh
make check    # Type-check
make build    # Native debug build
make release  # Optimized native build
make clean
```

The executable is `out/persona-blog`.

## Fly.io

`Dockerfile` performs a reproducible Linux build using a pinned reference Carp commit. `fly.toml` follows the deployment shape of `niarv.com` but does not require a volume.

The configured app name is `gzmask-persona-blog`. Change it if that Fly.io name is unavailable, then deploy:

```sh
fly apps create gzmask-persona-blog   # once
fly deploy --ha=false
```

The service listens on port `8080` and exposes `/health` for Fly health checks. Deploy with `--ha=false` to avoid Fly creating a second machine for high availability; the configured machine autostops when idle.
