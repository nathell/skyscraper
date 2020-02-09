# Caching

Skyscraper has two kinds of caches: one holding the raw downloaded HTML before parsing and processing (“HTML cache”), and one holding the results of parsing and processing individual pages (“processed cache”). Both caches are enabled by default, but can be disabled as needed.

In normal usage (i.e., scraping a site using code that is known to work), it is recommended to keep the processed cache enabled. The HTML cache can be disabled in this case without many drawbacks, as Skyscraper will not attempt to redownload a page that had been processed already. The advantage of disabling HTML cache is saving disk space: Web pages are typically markup-heavy and the interesting pieces constitute a tiny part of them, so the HTML cache can grow much faster than the processed cache. This can be problematic when scraping huge sites.

The converse is true when developing Skyscraper-based code and writing your own processors. The HTML cache comes in handy as you try out different ways of obtaining the desired information from the page at hand, as it only has to be downloaded once. On the other hand, disabling the processed cache guarantees that the information will be recomputed when the scraping code changes.

Skyscraper supports pluggable cache backends. The core library contains a protocol that the backends should conform to (`CacheBackend`), as well as two implementations of that protocol: one that doesn’t actually cache data, just pretending to be doing so (a “null cache”), and one that stores data in the filesystem. See the file `skyscraper/cache.clj` for details.

By default, both the HTML cache and the processed cache use the FS backend and are configured to live in `~/skyscraper-data`, respectively under `cache/html` and `cache/processed`.

## Cache keys

Every page cached by Skyscraper is stored under a cache key (a string). It is up to the user to construct a key in a unique way for each page, based on information available in the context. Typically, the key is hierarchical, containing a logical “path” to the page separated by slashes (it may or may not correspond to the page’s URL).

To facilitate construction of such keys, Skyscraper provides a micro-templating framework. The key templates can be specified in a `cache-template` parameter of the `defprocessor` macro. When a template contains parts prefixed by a colon and containing lower-case characters and dashes, these are replaced by corresponding context elements. As an example, the template `"mysite/:surname/:name"`, given the context `{:name "John", :surname "Doe"}`, will generate the key `"mysite/Doe/John"`.
