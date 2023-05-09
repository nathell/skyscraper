# Updating scraped sites

Often, once you successfully scrape a site in full, you then want to periodically update the scraped data: redownload and rescrape only what is necessary. Skyscraper offers several options to assist you.

## The brute-force way: wiping cache

If you’re using either of the HTML or processed caches (see [Caching][1]), then Skyscraper will reuse the already downloaded data for further processing. This means that rerunning a successful scrape with enabled caching will not trigger any HTTP request, even if the original site has changed.

The most obvious (but also slowest) way to proceed is by clearing the cache (e.g., `rm -r ~/skyscraper-data/cache`), forcing Skyscraper to redownload everything.

## The on-demand way: `:update` and `:updatable`

You can mark some processors as `:updatable`. These will typically correspond to non-leaf nodes of your scraping tree.

```
(defprocessor :landing-page
  :cache-template "mysite/index"
  :updatable true
  :process-fn …)
```

The value for `:updatable` can be either `true` (meaning “always update”), `false` (meaning “never update” – the default), or a function that should take a context and decide whether to update.

Just setting `:updatable` has no effect on its own. However, when you invoke [one of the scraping entry-points][2] with `:update` set to `true`, Skyscraper will force re-downloading and re-processing of an updatable page.

Sometimes, you will want to consult two versions of the document being processed: the one that was already present in cache and the freshly-downloaded one. The former is passed to the `:process-fn` as normal; to get the latter, you can call `cached-document` on the context. Note that your processor must be prepared for `cached-document` returning nil, indicating a first-time scrape. See `skyscraper.updates-test` for a contrived example.

## The optimization: `:uncached-only`

Regardless of whether `:update` is enabled or not, Skyscraper normally processes the whole site (some of it potentially coming from the cache). Sometimes, you want to prune the
scraping tree to uncached or updatable pages only, so that scraping only yields contexts corresponding to pages that are actually new.

The `:uncached-only` option to `scrape` does exactly that.

Be aware that in this mode scraping can do too little: pruning a page from the scraping tree also means pruning the entire subtree rooted at that page. Use it judiciously.

 [1]: caching.md
 [2]: scraping-modes.md
