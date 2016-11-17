# Skyscraper

## Structural scraping

What is structural scraping? Think of [Enlive]. It allows you to parse arbitrary HTML and extract various bits of information out of it: subtrees or parts of subtrees determined by selectors. You can then convert this information to some other format, easier for machine consumption, or process it in whatever other way you wish. This is called _scraping_.

Now imagine that you have to parse a lot of HTML documents. They all come from the same site, so most of them are structured in the same way and can be scraped using the same sets of selectors. But not all of them. There’s an index page, which has a different layout and needs to be treated in its own peculiar way, with pagination and all. There are pages that group together individual pages in categories. And so on. Treating single pages is easy, but with whole collections of pages, you quickly find yourself writing a lot of boilerplate code.

In particular, you realize that you can’t just `wget -r` the whole thing and then parse each page in turn. Rather, you want to simulate the workflow of a user who tries to “click through” the website to obtain the information she’s interested in. Sites have tree-like structure, and you want to keep track of this structure as you traverse the site, and reflect it in your output. I call it “structural scraping”.

## A look at Skyscraper

This is where Skyscraper comes in. Skyscraper grew out of quite a few one-off attempts to create machine-readable, clean “dumps” of different websites. Skyscraper builds on [Enlive] to process single pages, but adds abstractions to facilitate easy processing of entire sites.

Skyscraper can cache pages that it has already downloaded, as well as data extracted from those pages. Thus, if scraping fails for whatever reason (broken connection, OOM error, etc.), Skyscraper doesn’t have to re-download every page it has already processed, and can pick up off wherever it had been interrupted. This also facilitates updating scraped information without having to re-download the entire site.

Skyscraper is work in progress. This means that anything can change at any time. All suggestions, comments, pull requests, wishlists, etc. are welcome.

 [Enlive]: http://cgrand.github.com/enlive

The current release is 0.2.3. To use Skyscraper in your project, add the following to the `dependencies` section in your `project.clj`:

```
[skyscraper "0.2.3"]
```

 [NEWS.md]: https://github.com/nathell/skyscraper/blob/master/NEWS.md

## Contexts

A “context” is a map from keywords to arbitrary data. Think of it as “everything we have scraped so far”. A context has two special keys, `:url` and `:processor`, that contains the next URL to visit and the processor to handle it with (see below).

Scraping works by transforming context to list of contexts. You can think of it as a list monad. The initial list of contexts is supplied by the user, and typically contains a single map with an URL and a root processor.

A typical function producing an initial list of contexts (a _seed_) looks like this:

```clojure
(defn seed [& _]
  [{:url "http://www.example.com",
    :processor :root-page}])
```

## Processors

A “processor” is a unit of scraping: a function that processes sets of HTML pages in a uniform way.

A processor also performs janitorial tasks like downloading pages, storing them in the HTML cache, combining URLs, and caching the output. Processors are defined with the `defprocessor` macro (which expands to an invocation of the more elaborate `processor` function). A typical processor, for a site’s landing page that contains links to other pages within table cells, might look like this:

```clojure
(defprocessor landing-page
  :cache-template "mysite/index"
  :process-fn (fn [res context]
                (for [a (select res [:td :a])]
                  {:page (text a),
                   :url (href a),
                   :processor :subpage})))
```

The most important clause is `:process-fn`. This is the function called by the processor to extract new information from a page and include it in the context. It takes two parameters:

 1. an Enlive resource corresponding to the parsed HTML tree of the page being processed,
 2. the current context (i.e., combined outputs of all processors so far).

The output should be a seq of maps that each have a new URL and a new processor (specified as a keyword) to invoke next. If the output doesn’t contain the new URL and processor, it is considered a terminal node and Skyscraper will include it as part of the output. If the processor returns one context only, it is automatically wrapped in a seq.

In addition, `defprocessor` supports several other clauses that determine the pages that it visits and how they are cached. These are:

 - `:url-fn` – a one-argument function taking the context and returning the URL to visit. By default, Skyscraper just extracts the value under the `:url` key from the context.
 - `:cache-template` – a string specifying the template for cache keys (see below). Ignored when `:cache-key-fn` is specified.
 - `:cache-key-fn` – a function taking the context and returning the cache key (see below). Overrides `:cache-template`. Useful when mere templating does not suffice.
 - `:error-handler` – an error handler (see below).
 - `:updatable` – a boolean (false by default). When true, the pages accessed by this processor are considered to change often. When Skyscraper is run in update mode (see below), these pages will be re-downloaded and re-processed even if they had been present in the HTML or processed caches, respectively.
 - `:parse-fn` – a custom function that will be used to produce Enlive resources from HTMLs. This is useful rather rarely, e.g., when you’re scraping malformed HTML and need an interim fixup steps before parsing.

## Error handling

When Skyscraper downloads a page, a lot of things can go wrong. Specifically, one of the following situations may occur:

 - There is a timeout. In this case, Skyscraper will attempt to redownload the page (up to 5 times by default before it gives up, but this is configurable, see `:retries` below).
 - The download results in a HTTP status other than 200. In this case, Skyscraper will by default look at the error code. If it is 404, it will emit a warning and continue, pruning the scrape tree as if the processor had returned an empty seq. Otherwise, the error map obtained from clj-http will be rethrown (via Slingshot).

This default error handling strategy can be overridden, either globally by supplying the `:error-handler` option to `scrape`, or on a per-processor basis by putting it in `defprocessor`. If both are specified, the per-processor error handler prevails.

The argument to `:error-handler` should be a function that takes an URL and a clj-http error map (containing the `:status` key), and either throws an exception or returns a seq of contexts to be used as the processor’s output. See the function `default-error-handler` in Skyscraper’s source code for an example.

## Caching

Skyscraper has two kinds of caches: one holding the raw downloaded HTML before parsing and processing (“HTML cache”), and one holding the results of parsing and processing individual pages (“processed cache”). Both caches are enabled by default, but can be disabled as needed.

In normal usage (i.e., scraping a site using code that is known to work), it is recommended to keep the processed cache enabled. The HTML cache can be disabled in this case without many drawbacks, as Skyscraper will not attempt to redownload a page that had been processed already. The advantage of disabling HTML cache is saving disk space: Web pages are typically markup-heavy and the interesting pieces constitute a tiny part of them, so the HTML cache can grow much faster than the processed cache. This can be problematic when scraping huge sites.

The converse is true when developing Skyscraper-based code and writing your own processors. The HTML cache comes in handy as you try out different ways of obtaining the desired information from the page at hand, as it only has to be downloaded once. On the other hand, disabling the processed cache guarantees that the information will be recomputed when the scraping code changes.

Skyscraper supports pluggable cache backends. The core library contains a protocol that the backends should conform to (`CacheBackend`), as well as two implementations of that protocol: one that doesn’t actually cache data, just pretending to be doing so (a “null cache”), and one that stores data in the filesystem. See the file `skyscraper/cache.clj` for details.

There are other backends under construction, available as separate libraries. These include:

 - [skyscraper-cache-mapdb], a backend storing data in the [MapDB] key-value store

 [skyscraper-cache-mapdb]: https://github.com/nathell/skyscraper-cache-mapdb/
 [MapDB]: http://www.mapdb.org/

By default, both the HTML cache and the processed cache use the FS backend and are configured to live in `~/skyscraper-data`, respectively under `cache/html` and `cache/processed`.

## Templating

Every page cached by Skyscraper is stored under a cache key (a string). It is up to the user to construct a key in a unique way for each page, based on information available in the context. Typically, the key is hierarchical, containing a logical “path” to the page separated by slashes (it may or may not correspond to the page’s URL).

To facilitate construction of such keys, Skyscraper provides a micro-templating framework. The key templates can be specified in a `cache-template` parameter of the `defprocessor` macro (see above). When a template contains parts prefixed by a colon and containing lower-case characters and dashes, these are replaced by corresponding context elements. As an example, the template `"mysite/:surname/:name"`, given the context `{:name "John", :surname "Doe"}`, will generate the key `"mysite/Doe/John"`.

## Invocation and output

### `scrape`

The main entry point to Skyscraper is the `scrape` function. It is invoked as follows:

```clojure
(scrape seed params)
```

where `seed` is an initial seq of contexts (typically generated by a separate function) or a symbol or keyword naming such a function, and `params` are optional keyword parameters. The parameters currently supported are:

 - `:html-cache` (default `true`) – A CacheBackend implementation to use as the HTML cache. Can also be specified as a boolean value: `true` constructs a filesystem-backed cache (`FSCache`) with the default directory, while `false` signals a null cache (effectively disabling the HTML cache).
 - `:processed-cache` (default `true`) – Likewise, but for the processed cache.
 - `:http-options` – A map of additional options to pass to [clj-http], which Skyscraper uses under the hood to download pages from the Web. These options override the defaults, which are `{:as :auto, :socket-timeout 5000, :decode-body-headers true}` (set timeout to 5 seconds and autodetect the encoding). See clj-http’s documentation for other options you can pass here.
 - `:update` (default (`true`) – Runs Skyscraper in “update mode”. In this mode, Skyscraper will re-download and re-process pages whose processors are marked as `:updatable`, even if the corresponding keys are present in the HTML or processed caches.
 - `:retries` (default 5) – How many times Skyscraper will retry to download the page in the event of an error until it gives up.
 - `:only` – A map or seq of maps used to narrow down the output. For example, if you scrape the database of countries and cities where each country has its own page, then you can specify `:only {:country "France"}` to restrict the output to French cities only. This has the same effect as filtering the output of `scrape` manually, but is faster because it allows Skyscraper to prune the scraping tree. Alternatively, you can specify a predicate that will be used to filter results.
 - `:postprocess` – If specified, this function is called on the result seq of each processor to transform it before the next processor is run. This happens after results are filtered via `:only`.
 - `:error-handler` – An error handler for processors that don’t specify their own.

 [clj-http]: https://github.com/dakrone/clj-http

The output is a lazy seq of leaf contexts. This means that each map in this seq contains keys from every context that led to it being generated. For example, given the following scenario:

 - The first processor called by Skyscraper returns `[{:a 1, :url "someurl", :processor :processor2}]`
 - The second processor (`processor2`) returns `[{:b 2, :url "nexturl", :processor :processor3}]`
 - The third and final processor returns `[{:c 3} {:c 4}]`

Then the final output will be `({:a 1, :b 2, :c 3} {:a 1, :b 2, :c 4})`.

### Other functions

There are other functions that may be more convenient to use, depending on the application. These are:

 - `do-scrape`: Exactly like `scrape`, but takes a seed and an explicit map of options, instead of keyword arguments. `scrape` is in fact a very thin layer of syntactic sugar on top of `do-scrape`.
 - `scrape-csv`: Invoked as `(scrape-csv seed output-filename params)`. Saves the results of scraping to a CSV file named by `output-filename`. The first line of the file will contain column names (corresponding to leaf context keys) in an unspecified order, while the remaining lines will contain the corresponding data. Relies on the processed cache being enabled, as it invokes the scraping machinery twice (once to obtain a list of keys and once to generate the actual data), unless `:all-keys false` is specified, in which case only the keys of first scraped map will be present in the CSV file.
 - `get-cache-keys`: Invoked like `do-scrape`; runs scraping, but instead of normal output, returns a vector containing the generated cache keys and processors that produced them. Not lazy.

## Examples

More elaborate examples can be found in the `examples/` directory of the repo.

## Caveats

Skyscraper is work in progress. Some things are missing. The API is still in flux. Function and macro names, input and output formats are liable to change at any time. Suggestions of improvements are welcome (preferably as GitHub issues), as are pull requests.

## License

Copyright (C) 2015 Daniel Janus, http://danieljanus.pl

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
