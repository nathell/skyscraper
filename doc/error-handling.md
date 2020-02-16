# Error handling

When scraping, many things can go wrong (and, per [Murphy’s law][1], they will). The possible failures fall into two broad categories:

## Problems with processing (a processor throws an exception)

In this case, Skyscraper will gracefully propagate the exception to the scraping entry-point (i.e., it will be caught by the worker thread, all worker threads will terminate, and the exception will be re-thrown by the main thread after it has cleaned up).

## Problems with downloading (e.g., broken connections, HTTP 404 or 500 errors)

The behaviour here is customizable. The default logic is:

- if it’s an HTTP error other than a 50x, or a connection error, propagate the exception as above;
- if it’s an HTTP 50x error, retry up to `:retries` times (default 5), and after that propagate the exception.

You can override this by supplying a `:download-error-handler` option to the toplevel scraping function. If supplied, it should be a function taking three arguments:

- the exception as thrown by clj-http;
- the scraping option map as passed to the toplevel function;
- the context containing the `:url` on which scraping failed.

The error handler should return a vector of contexts that will be used as download results. Note that these contexts will _not_ normally be processed further – they will be returned as leaves of the scraping tree. To ignore the error, return an empty seq. To terminate scraping with an error, call `signal-error`. To simulate returning a successful clj-http response and continue as if the download had succeeded, call `respond-with`.

See `skyscraper.core/default-download-error-handler` for an example of how to implement such a function.

 [1]: https://en.wikipedia.org/wiki/Murphy%27s_law
