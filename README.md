# Skyscraper

Skyscraper is a Clojure library that helps you easily scrape multiple Web pages in a structural way. Skyscraper builds on [Enlive] to extract information from HTML webpages, and adds a framework to extract information from a set of pages linked together to form a structured Web site.

 - TODO more about “structural scraping”

Skyscraper can cache pages that it has already downloaded, as well as data extracted from those pages. Thus, if scraping fails for whatever reason (broken connection, OOM error, etc.), Skyscraper doesn’t have to re-download every page it has already processed, and can pick up off wherever it had been interrupted. This also facilitates updating scraped information without having to re-download the entire site.

Skyscraper is work in progress. This means that anything can change at any time. All suggestions, comments, pull requests, wishlists, etc. are welcome.

 [Enlive]: http://TODO

## Contexts

A “context” is a map from keywords to arbitrary data. Think of it as “everything we have scraped so far”. A context has one special key, `:url`, that contains the next URL to visit.

Scraping works by transforming context to list of contexts. It is essentially a list monad. The initial list of contexts is supplied by the user.

## Processors

## Caching

## Examples

More elaborate examples can be found in the `examples/` directory of the repo.

## License

Copyright (C) 2015 Daniel Janus, http://danieljanus.pl

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
