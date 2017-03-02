# History of Skyscraper releases

## Unreleased

- All options can now be provided either per-page or globally. (Thanks to Alexander Solovyov for the suggestion.)
- `get-cache-keys` has been removed. If you want the same effect, include `:cache-key` in the desired contexts.

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
