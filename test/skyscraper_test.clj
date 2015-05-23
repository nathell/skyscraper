(ns skyscraper-test
  (:require [clojure.test :refer :all]
            [skyscraper :refer [merge-urls]]))

(deftest test-merge-urls
  (are [y z] (= (merge-urls "https://foo.pl/bar/baz" y) z)
       "http://bar.uk/baz/foo" "http://bar.uk/baz/foo"
       "//bar.uk/baz/foo" "https://bar.uk/baz/foo"
       "/baz/foo" "https://foo.pl/baz/foo"
       "foo" "https://foo.pl/bar/baz/foo"))
