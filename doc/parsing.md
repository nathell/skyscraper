# Parsing

By default, Skyscrapers parses the downloaded documents into Enlive resources. However, this can be overridden by supplying a custom _parse function_.

Parse functions take a byte array (a binary blob) and a map of HTTP headers (clj-http’s [header map][1]), and should return a parsed representation of the document. Out of the box, Skyscraper provides three such functions:

 - `parse-enlive` – parses the blob as a HTML document with Enlive and returns an Enlive resource (a seq of maps);
 - `parse-reaver` – parses the blob as a HTML document with Reaver and returns an instance of `org.jsoup.nodes.Document`;
 - `parse-string` – parses the blob as a string.

As you can see, the parsed representation can be anything, as long as your processors can work with it. The output of the parse function will be fed to the processor’s `:process-fn` as the first argument.

You can specify a parse function in two ways:

 - for the whole scraping process – as a `:parse-fn` option to `scrape` or `scrape!`;
 - on a per-processor basis, in the `:parse-fn` clause in `defprocessor`.

If both are specified, the per-processor definition prevails. See `examples/tree_species.clj` for an example of a scraper that uses Reaver, and
`overridable-parse-fn-test` in `test/skyscraper/real_test.clj` for an example of a per-processor scraping function.

You can implement custom parse functions yourself, e.g., for parsing PDF document, CSV sheets, etc. If you are parsing a text-based format, call `parse-string` first and
process the resulting string – this will ensure that the blob is interpreted with a correct encoding as specified in the headers. Both `parse-enlive` and `parse-reaver` do this.

 [1]: https://github.com/dakrone/clj-http#headers
