# NOTE NOTE NOTE

This is an experimental branch of Skyscraper that is a major rewrite, using core.async, and will eventually become 0.3.0. This code should be currently considered unstable. Everything can break at any moment. The documentation below is obsolete and needs updating.

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

Processors are defined with the `defprocessor` macro (which registers the processing function in a global registry). A typical processor, for a site’s landing page that contains links to other pages within table cells, might look like this:

```clojure
(defprocessor :landing-page
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

The output should be a seq of maps that each have a new URL and a new processor (specified as a keyword) to invoke next.

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

By default, both the HTML cache and the processed cache use the FS backend and are configured to live in `~/skyscraper-data`, respectively under `cache/html` and `cache/processed`.

## Templating

Every page cached by Skyscraper is stored under a cache key (a string). It is up to the user to construct a key in a unique way for each page, based on information available in the context. Typically, the key is hierarchical, containing a logical “path” to the page separated by slashes (it may or may not correspond to the page’s URL).

To facilitate construction of such keys, Skyscraper provides a micro-templating framework. The key templates can be specified in a `cache-template` parameter of the `defprocessor` macro (see above). When a template contains parts prefixed by a colon and containing lower-case characters and dashes, these are replaced by corresponding context elements. As an example, the template `"mysite/:surname/:name"`, given the context `{:name "John", :surname "Doe"}`, will generate the key `"mysite/Doe/John"`.

## Invocation and output

## Examples

More elaborate examples can be found in the `examples/` directory of the repo.

## Caveats

Skyscraper is work in progress. Some things are missing. The API is still in flux. Function and macro names, input and output formats are liable to change at any time. Suggestions of improvements are welcome (preferably as GitHub issues), as are pull requests.

## License

Copyright (C) 2015 Daniel Janus, http://danieljanus.pl

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
