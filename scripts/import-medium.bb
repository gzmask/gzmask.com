#!/usr/bin/env bb

(ns import-medium
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def feed-url "https://medium.com/feed/@gzmask")
(def posts-directory "content/posts")
(def media-directory "public/media")
(def metadata-path "content/posts.json")
(def carp-index-path "posts.carp")

(defn child-elements [element local-name]
  (filter #(and (map? %) (= local-name (name (:tag %))))
          (:content element)))

(defn child [element local-name]
  (first (child-elements element local-name)))

(defn text-content [element]
  (apply str (filter string? (:content element))))

(defn post-id [url]
  (or (second (re-find #"([0-9a-f]{12})(?:\?.*)?$" url))
      (throw (ex-info "Could not extract Medium post ID" {:url url}))))

(def output-date-format
  (DateTimeFormatter/ofPattern "MMMM d, uuuu" Locale/ENGLISH))

(defn display-date [raw-date]
  (.format (ZonedDateTime/parse raw-date DateTimeFormatter/RFC_1123_DATE_TIME)
           output-date-format))

(defn extension-for [url content-type]
  (cond
    (or (str/includes? content-type "png") (re-find #"(?i)\.png(?:\?|$)" url)) "png"
    (or (str/includes? content-type "gif") (re-find #"(?i)\.gif(?:\?|$)" url)) "gif"
    (or (str/includes? content-type "webp") (re-find #"(?i)\.webp(?:\?|$)" url)) "webp"
    :else "jpg"))

(defn download-image! [post-id index url]
  (let [response (http/get url {:as :bytes
                                :follow-redirects :always
                                :headers {"User-Agent" "gzmask-persona-blog-importer/1.0"}})
        status (:status response)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Image download failed" {:url url :status status})))
    (let [content-type (get-in response [:headers "content-type"] "")
          extension (extension-for url content-type)
          filename (format "%s-%02d.%s" post-id (inc index) extension)
          output-path (str media-directory "/" filename)]
      (with-open [output (io/output-stream output-path)]
        (.write output ^bytes (:body response)))
      {:source url
       :public-path (str "/media/" filename)
       :output-path output-path})))

(def tracking-image-pattern
  #"(?is)<img\b[^>]*src=\"https://medium\.com/_/stat[^\"]*\"[^>]*>")

(def image-source-pattern
  #"(?is)<img\b[^>]*src=\"([^\"]+)\"[^>]*>")

(def script-pattern #"(?is)<script\b[^>]*>.*?</script>")

(defn localize-images! [post-id html]
  (let [without-trackers (str/replace html tracking-image-pattern "")
        without-scripts (str/replace without-trackers script-pattern "")
        urls (->> (re-seq image-source-pattern without-scripts)
                  (map second)
                  distinct
                  vec)
        images (mapv #(download-image! post-id %1 %2) (range) urls)
        localized (reduce (fn [body {:keys [source public-path]}]
                            (str/replace body source public-path))
                          without-scripts
                          images)]
    {:html (-> localized
               (str/replace #"(?i)<img\b" "<img loading=\"lazy\" decoding=\"async\"")
               (str/replace #"[\r\n]+" " ")
               str/trim)
     :images images}))

(defn parse-feed [xml-text]
  (let [root (xml/parse-str xml-text)
        channel (child root "channel")]
    (mapv
      (fn [item]
        (let [title (text-content (child item "title"))
              source-url (text-content (child item "link"))
              published-raw (text-content (child item "pubDate"))
              encoded (text-content (child item "encoded"))
              id (post-id source-url)
              localized (localize-images! id encoded)]
          {:id id
           :title title
           :published (display-date published-raw)
           :published-raw published-raw
           :html (:html localized)
           :images (mapv #(select-keys % [:public-path]) (:images localized))}))
      (child-elements channel "item"))))

(defn carp-string [value]
  (str "@\""
       (-> value
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r"))
       "\""))

(defn lookup-function [name key posts]
  (str "  (defn " name " [id]\n"
       "    (cond\n"
       (str/join "\n"
         (for [post posts]
           (str "      (= id \"" (:id post) "\") "
                (carp-string (get post key)))))
       "\n      @\"\"))\n\n"))

(defn write-carp-index! [posts]
  (let [entries (for [{:keys [id title published]} posts]
                  (str "    (PostMeta.init "
                       (carp-string id) " "
                       (carp-string title) " "
                       (carp-string published) ")"))
        source (str
                 ";; Generated by scripts/import-medium.bb. Do not edit by hand.\n\n"
                 "(deftype PostMeta [id String title String published String])\n\n"
                 "(defmodule Posts\n"
                 "  (sig all (Fn [] (Array PostMeta)))\n"
                 "  (defn all []\n"
                 "    [\n"
                 (str/join "\n" entries)
                 "])\n\n"
                 (lookup-function "title" :title posts)
                 (lookup-function "published" :published posts)
                 "  (defn exists? [id] (not (String.empty? &(title id)))))\n")]
    (spit carp-index-path source)))

(defn import! []
  (fs/create-dirs posts-directory)
  (fs/create-dirs media-directory)
  (let [feed-response (http/get feed-url {:headers {"User-Agent" "gzmask-persona-blog-importer/1.0"}})]
    (when-not (<= 200 (:status feed-response) 299)
      (throw (ex-info "Medium feed download failed" {:status (:status feed-response)})))
    (let [posts (parse-feed (:body feed-response))]
      (doseq [{:keys [id html]} posts]
        (spit (str posts-directory "/" id ".html") html))
      (spit metadata-path (json/generate-string (mapv #(dissoc % :html) posts)
                                                {:pretty true}))
      (write-carp-index! posts)
      (println (format "Imported %d posts and %d images"
                       (count posts)
                       (reduce + (map #(count (:images %)) posts)))))))

(import!)
