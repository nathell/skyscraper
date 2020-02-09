# NOTE NOTE NOTE

This repo is a pre-release state. Things mostly work, but documentation is in the process of being updated.

# Skyscraper

A framework that helps you build structured dumps of whole websites.

[![clojars](https://img.shields.io/clojars/v/skyscraper.svg)](https://clojars.org/skyscraper)
[![CircleCI](https://circleci.com/gh/nathell/skyscraper.svg?style=shield)](https://circleci.com/gh/nathell/skyscraper)
[![cljdoc](https://cljdoc.org/badge/nathell/skyscraper)](https://cljdoc.org/d/skyscraper/skyscraper/CURRENT)

## Concepts

### Structural scraping and scrape trees

Think of [Enlive]. It allows you to parse arbitrary HTML and extract various bits of information out of it: subtrees or parts of subtrees determined by selectors. You can then convert this information to some other format, easier for machine consumption, or process it in whatever other way you wish. This is called _scraping_.

Now imagine that you have to parse a lot of HTML documents. They all come from the same site, so most of them are structured in the same way and can be scraped using the same sets of selectors. But not all of them. There’s an index page, which has a different layout and needs to be treated in its own peculiar way, with pagination and all. There are pages that group together individual pages in categories. And so on. Treating single pages is easy, but with whole collections of pages, you quickly find yourself writing a lot of boilerplate code.

In particular, you realize that you can’t just `wget -r` the whole thing and then parse each page in turn. Rather, you want to simulate the workflow of a user who tries to “click through” the website to obtain the information she’s interested in. Sites have tree-like structure, and you want to keep track of this structure as you traverse the site, and reflect it in your output. I call it “structural scraping”, and the tree of traversed pages and information extracted from each one – the “scrape tree”.

### Contexts

A “context” is a map from keywords to arbitrary data. Think of it as “everything we have scraped so far”. A context has two special keys, `:url` and `:processor`, that contains the next URL to visit and the processor to handle it with (see below).

Scraping works by transforming context to list of contexts. You can think of it as a list monad. The initial list of contexts is supplied by the user, and typically contains a single map with an URL and a root processor.

A typical function producing an initial list of contexts (a _seed_) looks like this:

```clojure
(defn seed [& _]
  [{:url "http://www.example.com",
    :processor :root-page}])
```

### Processors

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

## Where to go from here

Explore the [documentation]. Have a look at examples in the `examples/` directory of the repo. Read the docstrings, especially those of `scrape` and `defprocessor`.

If something is unclear, or you have suggestions or encounter a bug, please create an issue!

 [documentation]: https://cljdoc.org/d/skyscraper/skyscraper/

## Caveats

Skyscraper is work in progress. Some things are missing. The API is still in flux. Function and macro names, input and output formats are liable to change at any time. Suggestions of improvements are welcome (preferably as GitHub issues), as are pull requests.

## License

Copyright (C) 2015–2020 Daniel Janus, http://danieljanus.pl

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
