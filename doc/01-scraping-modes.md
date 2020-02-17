# Scraping modes and asynchrony

## Entry points

Skyscraper is implemented using [core.async][1] and by default, scraping happens asynchronously on multiple threads. There are several entry-point functions to invoke scraping; they all take the same arguments (see the docstring of the first one for details), but differ in how they return results.

### `skyscraper.core/scrape`: lazy sequence of contexts

This is the only mode that was available in Skyscraper versions prior to 0.3. The `scrape` function returns a lazy sequence of contexts that are leaves in the scraping tree. After fully consuming this sequence, the underlying core.async channel will be closed and the worker threads terminated.

### `skyscraper.core/scrape!`: imperative, eager

While `scrape` tries to provide a functional, sequence-based interface, `scrape!` takes the stance that scraping is a side-effectful process. It is eager (returns after scraping has completed) and returns `nil`. There are two ways to actually access the scraping results:

 1. Provide the `:db` or `:db-file` options. This will cause `scrape!` to output a SQLite database. See [details][2].
 2. Provide the `:leaf-chan` or `:item-chan` options: core.async channels to which vectors of leaf contexts or interim contexts, respectively, will be delivered.

### `skyscraper.dev/scrape`: for interactive use in the REPL

This one is described in [Development Mode][3].

### `skyscraper.traverse/launch`: advanced

Scraping in Skyscraper is actually implemented atop a more primitive abstraction: parallelized context tree traversal (see the docstring on `skyscraper.traverse`). All other scraping functions are implemented on top of `launch`. Use it in the rare cases when you want strict control over when to terminate scraping.

## Asynchrony

Scraping spins up a number of _worker threads_ that actually perform scraping (parsing HTML, running processors, and sometimes downloading data – see below). The number of worker threads is configurable: by default, it’s 4, but you can override it by the `:parallelism` setting.

## Download modes

By default, Skyscraper downloads pages using clj-http’s async request facility. This means that the downloads actually happen on threads other than the ones managed by Skyscraper. You can change this by specifying `:download-mode :sync`.

When `:download-mode` is `:async` mode, there can be more simultaneous downloads than Skyscraper’s worker threads. Use `:max-connections` to limit that number.

Use `:parallelism 1 :download-mode :sync` to simulate sequential scraping flow à la Skyscraper 0.2. This is useful when you want to precisely control the order in which your pages will be scraped.

 [1]: https://github.com/clojure/core.async
 [2]: db.md
 [3]: development-mode.md
