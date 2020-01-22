# Development mode: Developing scrapers interactively

Here’s a common workflow for developing a scraper with Skyscraper:

1. Start with a seed.
2. Download and parse the first page in that seed.
3. Experiment in the REPL with extracting desired data out of that page.
4. Once you’re satisfied with the outcome, define a processor.
5. Proceed with scraping until a page with yet-undefined processor is encountered.
6. Repeat steps 3–5 until all processors are defined.

Skyscraper provides some functions (in the namespace `skyscraper.dev`) to assist in this process.

## A worked example

As an example, we will develop a scraper to download and extract the data from UK Forest Research’s tree species database. At the time of writing (7 January 2020), you can access it here: https://www.forestresearch.gov.uk/tools-and-resources/tree-species-database/

The complete code for this example can be found in the `examples/` directory.

Launch a REPL and create a new namespace:

```clojure
(ns skyscraper.examples.tree-species
  (:require [reaver]
            [skyscraper.core :as core :refer [defprocessor]]))

(require '[skyscraper.dev :refer :all])
```

Note that we have referred to the `skyscraper.dev` namespace for development; we’ll remove that `require` later, when we’re done. We have also opted to use Reaver (rather than the default Enlive) as the HTML parser.

Define the seed:

```clojure
(def seed [{:url "https://www.forestresearch.gov.uk/tools-and-resources/tree-species-database"
            :processor :tree-list
            :page 1}])
```

We start at the first page of a paginated list, containing links to pages describing individual tree species as well as to the next page.

At this point, we can run our first scrape, even though we haven’t defined the `:tree-list` processor yet:

 ```clojure
(scrape seed :parse-fn core/parse-reaver)
```

Skyscraper will run for a while and eventually say:
```
20-01-08 10:07:18 serpent INFO [skyscraper.dev:48] - Scraping suspended in processor :tree-list
```
It will also helpfully open a browser for you, pointed at a local copy of the page it doesn’t know how to parse yet. (The styling will usually be broken, but it won’t affect your ability to extract the data).

Use your browser’s DevTools to look at that page. You’ll notice that each tree species has its own div of class `listing-item`, which are all contained in the div of class `listing--trees`. Let us test this hypothesis in the REPL:

```clojure
(count (reaver/select (document) ".listing--trees"))
;=> 1
(count (reaver/select (document) ".listing--trees .listing-item"))
;=> 10
```

Note that we call `document` here, and it returns the Reaver parse tree of the page that you were looking at in the browser.

Let’s try to extract the data. For each species, we want the English name, Latin name, and a link to the details page:

```clojure
(reaver/extract-from (document) ".listing--trees .listing-item"
                     [:english-name :latin-name :url]
                     ".listing__heading" reaver/text
                     ".listing__metadata" reaver/text
                     ".listing-item" (reaver/attr :href))
;=> ({:english-name "Common (or black) alder (CAR)",
;     :latin-name "Alnus glutinosa",
;     :url "/tools-and-resources/tree-species-database/common-or-black-alder-car/"}
;    ...)
```

Let’s also see if we can find a link to the next page:

```clojure
(-> (reaver/select (document) ".pagination__item--next")
    first
    (reaver/attr :href))
;=> "/tools-and-resources/tree-species-database/page/2/"
```

We now have everything we need to define a processor:

```clojure
(defprocessor :tree-list
  :process-fn (fn [doc ctx]
                (concat
                 (reaver/extract-from doc ".listing--trees .listing-item"
                                      [:english-name :latin-name :url :processor]
                                      ".listing__heading" reaver/text
                                      ".listing__metadata" reaver/text
                                      ".listing-item" (reaver/attr :href)
                                      ".listing-item" (constantly :tree))
                 (when-let [next-page-url (-> (reaver/select doc ".pagination__item--next") first (reaver/attr :href))]
                   [{:url next-page-url
                     :processor :tree-list
                     :page (inc (:page ctx))}]))))
```

Note that we have added the next processor name to the `extract-from` invocation, and also replaced the `(document)` calls (meant to be used interactively) with the first argument of `process-fn`.

You can now check whether this processor works correctly on the current resource and context:

```clojure
(run-last-processor)
```

It should return 11 maps: 10 species plus the next page of the list. All good so far! We are ready to proceed.

Re-run the scrape as before:

```clojure
(scrape seed :parse-fn core/parse-reaver)
```

Skyscraper will clean up after the previous attempt and suspend scraping on the species detail page this time. For the sake of example, we will do the simplest thing and just grab the HTML description:

```clojure
(defprocessor :tree
  :process-fn (fn [doc ctx]
                {:description-html (when-let [description (reaver/select doc ".is-typeset--article")]
                                     (.html description))}))
```

And we’re done! If you launch the scrape again, it will now run to completion. We are now ready to remove the `(require 'skyscraper.dev)` line and run the usual `skyscraper.core/scrape` function to obtain structured data.

The complete code in `examples/` also contains cache templates for both processors.

## Implementation note

Development mode is currently implemented using `item-chan`. This means that you can’t pass a custom `item-chan` to `skyscraper.dev/scrape` because it will be overridden.

It also means that when you redefine a processor after a suspended `scrape` and then call `scrape` again, it is unable to just continue as if the new definition had been in effect all along. Instead, it will run the previous scrape to completion and then start a new one. This should not be a big problem in practice, but it may cause Skyscraper to download pages more often than you might expect. Enable HTML cache to remedy this.

In development mode, `parallelism` is set to 1.
