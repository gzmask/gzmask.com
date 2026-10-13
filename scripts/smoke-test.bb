#!/usr/bin/env bb

(ns smoke-test
  (:refer-clojure :exclude [ensure])
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def base-url "http://127.0.0.1:8080")
(def visitor-counter-file "visitor-count.txt")

(defn start-server []
  (process/process ["./out/persona-blog"]
                   {:out :inherit :err :inherit}))

(defn request [path options]
  (http/get (str base-url path) (merge {:throw false} options)))

(defn wait-until-ready []
  (loop [attempt 0]
    (when (= attempt 50)
      (throw (ex-info "Server did not become ready" {})))
    (let [response (try
                     (request "/health" {})
                     (catch Exception _ nil))]
      (if (= 200 (:status response))
        response
        (do (Thread/sleep 100) (recur (inc attempt)))))))

(defn ensure [condition message]
  (when-not condition
    (throw (ex-info message {}))))

(io/delete-file visitor-counter-file true)
(let [server (atom (start-server))]
  (try
    (wait-until-ready)
    (let [index (request "/" {})
          index-body (:body index)]
      (ensure (= 200 (:status index)) "Index did not return HTTP 200")
      (ensure (str/includes? index-body "寻羊，西部世界第二配曲，先锋CS-77和Ikea stockholm 媒体柜")
              "Index is missing the first-party post title")
      (ensure (str/includes? index-body "Why Clojure works")
              "Index is missing a post title")
      (ensure (not (str/includes? index-body "biggest functional programming advocate"))
              "Index eagerly included a post body")
      (ensure (not (str/includes? index-body "/media/"))
              "Index eagerly included post media")
      (ensure (str/includes? index-body "/navigation.js")
              "Index is missing client-side scroll restoration")
      (ensure (str/includes? index-body "SITE VISITS")
              "Index is missing the visitor counter")
      (ensure (str/includes? index-body "<output id='visitor-count'>1</output>")
              "First server-rendered page view should be visitor count 1"))

    (let [posts (json/parse-string (slurp "content/posts.json") true)]
      (ensure (= 113 (count posts)) "Expected the complete 113-post archive")
      (ensure (= 72 (count (filter #(= "wordpress" (:source %)) posts)))
              "Expected all 72 non-duplicate WordPress posts")
      (ensure (= 30 (count (filter #(= "rednote" (:source %)) posts)))
              "Expected all 30 non-video Rednote posts")
      (ensure (= 1 (count (filter #(= "local" (:source %)) posts)))
              "Expected the first-party post")
      (doseq [{:keys [id title images]} posts]
        ;; Datastar reads `elements` from one SSE data line; a newline in a
        ;; fragment silently drops everything after it from the DOM patch.
        (ensure (not (re-find #"[\r\n]" (slurp (str "content/posts/" id ".html"))))
                (str "Post HTML must be a single line for Datastar: " id))
        (let [article (request (str "/posts/" id) {})]
          (ensure (= 200 (:status article)) (str "Post route failed: " id))
          (ensure (str/includes? (:body article) title)
                  (str "Post title missing from direct route: " id))
          (ensure (not (str/includes? (:body article) "medium.com"))
                  (str "Post page links back to Medium: " id))
          (ensure (= 1 (count (re-seq #"class='archive-orb'" (:body article))))
                  (str "Post must have exactly one round Archive control: " id)))
        (doseq [{:keys [public-path remote-url]} images]
          (if public-path
            (let [image (request public-path {:as :bytes})]
              (ensure (= 200 (:status image))
                      (str "Localized image unavailable: " public-path)))
            (ensure (str/starts-with? remote-url "https://sns-img-qc.xhscdn.com/")
                    (str "Invalid Rednote image URL: " remote-url))))))

    (let [navigation (request "/navigation.js" {})]
      (ensure (= 200 (:status navigation)) "Navigation script did not return HTTP 200")
      (ensure (str/includes? (:body navigation) "gzmaskArchiveScrollY")
              "Navigation script does not retain the archive scroll position")
      (ensure (not (str/includes? (:body navigation) "localStorage"))
              "Visitor count must not be stored in the browser"))

    (let [rednote-post (request "/posts/rednote-6abefbbd000000000f03a800" {})]
      (ensure (= 200 (:status rednote-post)) "Rednote post did not return HTTP 200")
      (ensure (str/includes? (:body rednote-post) "#johnburrows")
              "Rednote post body is missing its description")
      (ensure (str/includes? (:body rednote-post) "https://sns-img-qc.xhscdn.com/")
              "Rednote post is missing remotely hosted images")
      (ensure (= 14 (count (re-seq #"<figure>" (:body rednote-post))))
              "Rednote post is missing images"))

    (let [local-post (request "/posts/20261012-sheep-cs77"
                              {:headers {"Accept" "text/event-stream"
                                         "Datastar-Request" "true"}})
          body (:body local-post)
          elements-line (first (filter #(str/starts-with? % "data: elements ")
                                       (str/split-lines body)))]
      (ensure (= 200 (:status local-post)) "First-party Datastar post did not return HTTP 200")
      (ensure (= 4 (count (re-seq #"<figure>" body)))
              "First-party Datastar post is missing images")
      (ensure (and elements-line
                   (str/includes? elements-line "class='archive-orb'")
                   (str/includes? elements-line "</main>"))
              "First-party Datastar patch must contain the complete article and round Archive control")
      (ensure (not (re-find #"(?m)^data: <(?:p|figure)" body))
              "First-party paragraphs and figures must not be split across SSE data lines"))

    (let [post (request "/posts/1fbf49c8c32c" {})]
      (ensure (= 200 (:status post)) "Post did not return HTTP 200")
      (ensure (str/includes? (:body post) "biggest functional programming advocate")
              "Post body was not loaded"))

    (let [wordpress-post (request "/posts/wp-49" {})]
      (ensure (= 200 (:status wordpress-post)) "Oldest WordPress post did not return HTTP 200")
      (ensure (str/includes? (:body wordpress-post) "domain registration catch program")
              "Oldest WordPress post body was not loaded"))

    (let [sse (request "/posts/841f41dc2430"
                       {:headers {"Accept" "text/event-stream"
                                  "Datastar-Request" "true"}})
          body (:body sse)]
      (ensure (= 200 (:status sse)) "Datastar request did not return HTTP 200")
      (ensure (str/starts-with? body "event: datastar-patch-elements\n")
              "Datastar response is missing its event type")
      (ensure (str/includes? body "data: useViewTransition true\n")
              "Datastar response does not enable view transitions")
      (ensure (str/includes? body "成都两日")
              "Datastar response is missing post content")
      (ensure (str/includes? body "id='visitor-count'")
              "Datastar response is missing the updated server-side count"))

    (let [image (request "/media/841f41dc2430-01.jpg" {:as :bytes})
          missing (request "/posts/not-a-post" {})]
      (ensure (= 200 (:status image)) "Localized post image is unavailable")
      (ensure (= 404 (:status missing)) "Missing posts must return HTTP 404"))

    (let [count-before-restart (Long/parseLong (slurp visitor-counter-file))]
      (process/destroy-tree @server)
      (reset! server (start-server))
      (wait-until-ready)
      (let [restarted-index (request "/" {})
            expected-count (inc count-before-restart)]
        (ensure (str/includes? (:body restarted-index)
                               (str "<output id='visitor-count'>" expected-count "</output>"))
                "Visitor count did not persist across a server restart")))

    (println "Smoke test passed: server-side file-persisted counter, 113 posts, media, Datastar SSE, and 404s")
    (finally
      (process/destroy-tree @server)
      (io/delete-file visitor-counter-file true))))
