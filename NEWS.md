# History of Skyscraper releases

## Unreleased

- A processor can now return one context only. (Thanks to Bryan Maass.)
- The `processed-cache` option now works as advertised.

## 0.1.1 (2015-08-24)

- Processors (`process-fn` functions) can now access current context.
- Skyscraper now uses [clj-http] to issue HTTP GET requests.
  - Skyscraper can now auto-detect page encoding thanks to clj-http’s `decode-body-headers` feature.
  - `scrape` now supports a `http-options` argument to override HTTP options (e.g., timeouts).
- Skyscraper’s output is now fully lazy (i.e., guaranteed to be non-chunking).
- Fixed a bug where relative URLs were incorrectly resolved in certain circumstances.

 [clj-http]: https://github.com/dakrone/clj-http

## 0.1.0 (2015-08-11)

- First public release.
