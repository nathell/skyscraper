(ns skyscraper.db-test
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.test :as test :refer [deftest is testing]]
    [skyscraper.db :as db]
    [skyscraper.test-utils :refer [with-temporary-sqlite-db]]))

(deftest test-insert-all!
  (let [example-data [{:name "John", :surname "Doe", :phone "123-45-67"}
                      {:name "Donald", :surname "Covfefe", :phone "666-66-66"}]]
    (testing "basic usecase"
      (with-temporary-sqlite-db db
        (db/insert-all! db :example-data nil [:name :surname :phone] example-data)
        (is (= (jdbc/query db "SELECT name, surname, phone FROM example_data ORDER BY id")
               example-data))))
    (testing "multiple inserts duplicate data"
      (with-temporary-sqlite-db db
        (dotimes [_ 5]
          (db/insert-all! db :example-data nil [:name :surname :phone] example-data))
        (is (= (jdbc/query db "SELECT count(*) cnt FROM example_data")
               [{:cnt 10}])))
      (testing "unless key is supplied"
        (with-temporary-sqlite-db db
          (dotimes [_ 5]
            (db/insert-all! db :example-data [:name :surname] [:name :surname :phone] example-data))
          (is (= (jdbc/query db "SELECT count(*) cnt FROM example_data")
                 [{:cnt 2}])))))))
