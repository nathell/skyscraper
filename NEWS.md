# History of Skyscraper releases

## 0.3.2 (2022-08-04)

- Fix: Skyscraper no longer throws exceptions when using processed-cache
  and some of the processors don’t have `:cache-template`.
- Fix: Skyscraper no longer throws exceptions when the server returns
  multiple Content-Type headers.
- Fix: Processed cache no longer garbles non-ASCII strings on macOS.

## 0.3.1 (2022-07-31)

- Backwards-incompatible API changes:
  - `parse-fn` is now expected to take three arguments, the third being
    the context. The aim of this change is to support cases where the
    HTML is known to be malformed and needs context-aware preprocessing
    before parsing. Built-in parse-fns have been updated to take the
    additional argument.
  - Cache backends are now expected to implement `java.io.Closeable`
    in addition to `CacheBackend`. Built-in backends have been
    updated to include no-op `close` methods.
- Optimization: Skyscraper no longer generates indexes for columns
  marked with `:skyscraper.db/key-columns` when creating the DB from
  scratch. There is also a new option, `:ignore-db-keys`, to force
  this at all times.
- Skyscraper now retries downloads upon encountering a timeout.
- Bug fixes:
  - Fixed dev/scrape misbehaving when redefining processors while scraping is suspended.
  - Fixed scrape mishandling errors with `:download-mode` set to `:sync`.
  - Fixed an off-by-one bug in handling `:retries`.
  - Retry counts are now correctly reset on successful download.

## 0.3.0 (2020-02-17)

- Skyscraper has been rewritten from scratch to be asynchronous and multithreaded,
  based on [core.async].  See [Scraping modes] for details.
- Skyscraper now supports saving the scrape results to [a SQLite database][db].
- In addition to the classic `scrape` function that returns a lazy sequence of nodes, there is an
  alternative, non-lazy, imperative interface (`scrape!`) that treats producing new results as
  side-effects.
- [reaver] (using JSoup) is now available as an optional underlying HTML parsing engine, as an alternative to Enlive.
- `:parse-fn` and `:http-options` can now be provided either per-page or globally. (Thanks to Alexander Solovyov for the suggestion.)
- All options are now optional, including sane default for `process-fn`.
- Backwards-incompatible API changes:
  - The `skyscraper` namespace has been renamed to `skyscraper.core`.
  - Processors are now named by keywords.
    - `defprocessor` now takes a keyword name, and registers a function in the
      global registry instead of defining it. This means that it’s no longer possible
      to call one processor from another: if you need that, define `process-fn` as a
      named function.
    - The context values corresponding to `:processor` keys are now expected to
      be keywords.
  - `scrape` no longer guarantees the order in which the site will be scraped.
    In particular, two different invocations of `scrape` are not guaranteed to return
    the scraped data in the same order. If you need that guarantee, set
    `parallelism` and `max-connections` to 1.
  - The cache interface has been overhauled. Caching now works by storing binary blobs
    (rather than strings), along with metadata (e.g., HTTP headers). Caches created
    by Skyscraper 0.1 or 0.2 cannot be reused for 0.3.
  - [Error handling] has been reworked.
  - `get-cache-keys` has been removed. If you want the same effect, include `:cache-key` in the desired contexts.

 [core.async]: https://github.com/clojure/core.async
 [Scraping modes]: doc/01-scraping-modes.md
 [db]: doc/07-db.md
 [Error handling]: doc/06-error-handling.md
 [reaver]: https://github.com/mischov/reaver

## 0.2.3 (2016-11-17)

- New feature: Custom parse functions.
- New feature: Customizable error handling strategies.
- Bugfix: `:only` now doesn’t barf on keys not appearing in seed.

## 0.2.2 (2016-05-06)

- Skyscraper now uses Timbre for logging.
- New cache backend: `MemoryCache`.
- `download` now supports arbitrarily many retries.
- A situation where a context has a processor but no URL now triggers a warning instead of throwing an exception.

## 0.2.1 (2015-12-17)

- New function: `get-cache-keys`.
- `scrape` and friends can now accept a keyword as the first argument.
- Cache keys are now accessible from within processors (under the
  `:cache-key` key in the context).
- New `scrape` options: `:only` and `:postprocess`.
- `scrape-csv` now accepts an `:all-keys` argument and has been rewritten using a helper function, `save-dataset-to-csv`.

## 0.2.0 (2015-10-03)

- Skyscraper now supports pluggable cache backends.
- The caching mechanism has been completely overhauled and Skyscraper no longer
  creates temporary files when the HTML cache is disabled.
- Support for capturing scraping results to CSV via `scrape-csv`.
- Support for updating existing scrapes: new processor flag `:updatable`,
  `scrape` now has an `:update` option.
- New `scrape` option: `:retries`.
- Fixed a bug whereby scraping huge datasets would result in an `OutOfMemoryError`.
  (`scrape` no longer holds onto the head of the lazy seq it produces).

## 0.1.2 (2015-09-17)

- A processor can now return one context only. (Thanks to Bryan Maass.)
- The `processed-cache` option to `scrape` now works as advertised.
- New `scrape` option: `:html-cache`. (Thanks to ayato-p.)
- Namespaced keywords are now resolved correctly to processors.
  (Thanks to ayato-p.)
- New official `defprocessor` clauses: `:url-fn` and `:cache-key-fn`.
  - Note: these clauses existed in previous versions but were undocumented.
- All contexts except the root ones are now guaranteed to contain the `:url` key.

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
