(ns skyscraper.db-test
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.test :as test :refer [deftest is testing]]
    [skyscraper.db :as db]
    [skyscraper.test-utils :refer [with-temporary-sqlite-db]]))

(deftest test-insert-all!
  (let [example-data [{:name "John", :surname "Doe", :phone "123-45-67"}
                      {:name "Donald", :surname "Covfefe", :phone "666-66-66"}]
        update [{:name "John", :surname "Doe", :phone "765-43-21"}]]
    (testing "basic usecase"
      (with-temporary-sqlite-db db
        (db/upsert-contexts db :example-data nil [:name :surname :phone] example-data)
        (is (= (jdbc/query db "SELECT name, surname, phone FROM example_data ORDER BY id")
               example-data))))
    (testing "multiple inserts duplicate data"
      (with-temporary-sqlite-db db
        (dotimes [_ 5]
          (db/upsert-contexts db :example-data nil [:name :surname :phone] example-data))
        (is (= (jdbc/query db "SELECT count(*) cnt FROM example_data")
               [{:cnt 10}])))
      (testing "unless key is supplied"
        (with-temporary-sqlite-db db
          (dotimes [_ 5]
            (db/upsert-contexts db :example-data [:name :surname] [:name :surname :phone] example-data))
          (is (= (jdbc/query db "SELECT count(*) cnt FROM example_data")
                 [{:cnt 2}])))))
    (testing "updates"
      (with-temporary-sqlite-db db
        (doseq [data [example-data update]]
          (db/upsert-contexts db :example-data [:name :surname] [:name :surname :phone] data))
        (is (= (jdbc/query db "SELECT name, surname, phone FROM example_data ORDER BY id")
               [(first update) (second example-data)]))))
    (testing "upsert empty contexts is a no-op"
      (with-temporary-sqlite-db db
        (is (empty? (db/upsert-contexts db :example-data [:name :surname] [:name :surname :phone] [])))))
    (testing "columns same as key-columns"
      (with-temporary-sqlite-db db
        (dotimes [_ 5]
          (db/upsert-contexts db :example-data [:name :surname] [:name :surname] example-data))
        (is (= (jdbc/query db "SELECT count(*) cnt FROM example_data")
               [{:cnt 2}]))))))
