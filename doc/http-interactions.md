# HTTP interactions

By default, Skyscraper makes HTTP requests with the GET method. However, it is possible to override it and use any method you want: just return a context with `:http/method` key from your processor. Set it to `:post`, `:put`, `:patch`, or whatever have you.

Skyscraper actually doesn’t have any explicit support for the `:http/method` field. Instead, it just extracts any fields with the `http` namespace from contexts, and passes them as arguments to clj-http’s [`request`][1] function (unnamespacing them). This means that, for example, you can login to a password-protected site by combining `:http/method :post` and sending your credentials in `:http/form-params`.

For example:

```clojure
(defprocessor :login
  :process-fn (fn [document context]
                [{:url "/login",
                  :http/method :post,
                  :http/form-params {:username "johndoe", :password "t0p$3cr3+"}}]))
```

Note that, unlike most keys in contexts, the `http`/-namespaced keys are one-off: they don’t get propagated to subsequent processors further down in the scraping tree. So if you tell Skyscraper to send a POST request to grab some page, and the processor for that page returns contexts pointing to other pages, those will again be requested with GET unless you
set the method explicitly.

The one exception for this rule is `:http/cookies`. Skyscraper will automatically merge cookies from HTTP responses into the map stored under that key, will preserve it across contexts, and clj-http’s API means they will be posted back to the server on every request. Thus, for the most part, you don’t need to worry about cookies: they Just Work™.

 [1]: https://cljdoc.org/d/clj-http/clj-http/3.10.0/api/clj-http.client
