#!/usr/bin/env bb

(ns import-archive
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDateTime ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def medium-feed-url "https://medium.com/feed/@gzmask")
(def wordpress-api-url
  "https://public-api.wordpress.com/wp/v2/sites/gzmask.wordpress.com/posts?per_page=100&_fields=id,date,link,title,content")
(def posts-directory "content/posts")
(def media-directory "public/media")
(def metadata-path "content/posts.json")
(def carp-index-path "posts.carp")

(def request-options
  {:follow-redirects :always
   :headers {"User-Agent" "gzmask-persona-blog-importer/2.0"}})

(def output-date-format
  (DateTimeFormatter/ofPattern "MMMM d, uuuu" Locale/ENGLISH))

(def named-html-entities
  {"&amp;" "&"
   "&apos;" "'"
   "&gt;" ">"
   "&lt;" "<"
   "&nbsp;" " "
   "&quot;" "\""})

(defn decode-numeric-entity [[_ radix-value decimal-value]]
  (let [codepoint (Long/parseLong (or radix-value decimal-value)
                                  (if radix-value 16 10))]
    (String. (Character/toChars codepoint))))

(defn decode-html-entities [value]
  (-> (reduce-kv str/replace value named-html-entities)
      (str/replace #"&#(?:x([0-9a-fA-F]+)|(\d+));" decode-numeric-entity)))

(defn html-safe-title [value]
  (-> (decode-html-entities value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      str/trim))

(defn normalized-title [value]
  (-> value decode-html-entities str/lower-case str/trim))

(defn fetch-text [url]
  (let [response (http/get url request-options)]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Archive download failed" {:url url :status (:status response)})))
    (:body response)))

(defn child-elements [element local-name]
  (filter #(and (map? %) (= local-name (name (:tag %))))
          (:content element)))

(defn child [element local-name]
  (first (child-elements element local-name)))

(defn text-content [element]
  (apply str (filter string? (:content element))))

(defn medium-post-id [url]
  (or (second (re-find #"([0-9a-f]{12})(?:\?.*)?$" url))
      (throw (ex-info "Could not extract Medium post ID" {:url url}))))

(defn medium-display-date [raw-date]
  (.format (ZonedDateTime/parse raw-date DateTimeFormatter/RFC_1123_DATE_TIME)
           output-date-format))

(defn wordpress-display-date [raw-date]
  (.format (LocalDateTime/parse raw-date) output-date-format))

(defn sort-key [raw-date]
  (try
    (.toEpochMilli (.toInstant
                     (ZonedDateTime/parse raw-date DateTimeFormatter/RFC_1123_DATE_TIME)))
    (catch Exception _
      (.toEpochMilli (.toInstant (LocalDateTime/parse raw-date) ZoneOffset/UTC)))))

(defn extension-for [url content-type]
  (cond
    (or (str/includes? content-type "png") (re-find #"(?i)\.png(?:\?|$)" url)) "png"
    (or (str/includes? content-type "gif") (re-find #"(?i)\.gif(?:\?|$)" url)) "gif"
    (or (str/includes? content-type "webp") (re-find #"(?i)\.webp(?:\?|$)" url)) "webp"
    :else "jpg"))

(defn download-image! [post-id index source]
  (let [url (-> source
                decode-html-entities
                (str/replace-first #"^http://" "https://"))]
    (try
      (let [response (http/get url (assoc request-options :as :bytes :throw false))
            status (:status response)
            content-type (get-in response [:headers "content-type"] "")]
        (if (and (<= 200 status 299) (str/starts-with? content-type "image/"))
          (let [extension (extension-for url content-type)
                filename (format "%s-%02d.%s" post-id (inc index) extension)
                output-path (str media-directory "/" filename)]
            (with-open [output (io/output-stream output-path)]
              (.write output ^bytes (:body response)))
            {:source source
             :public-path (str "/media/" filename)
             :output-path output-path})
          (do
            (binding [*out* *err*]
              (println (format "Warning: could not archive image (%s, %s): %s"
                               status content-type url)))
            nil)))
      (catch Exception exception
        (binding [*out* *err*]
          (println (format "Warning: could not archive image (%s): %s"
                           (.getMessage exception) url)))
        nil))))

(def tracking-image-pattern
  #"(?is)<img\b[^>]*src=\"https://medium\.com/_/stat[^\"]*\"[^>]*>")

(def image-source-pattern
  #"(?is)<img\b[^>]*src=[\"']([^\"']+)[\"'][^>]*>")

(def script-pattern #"(?is)<script\b[^>]*>.*?</script>")
(def responsive-image-attributes-pattern
  #"(?is)\s+(?:srcset|sizes|loading|decoding)=[\"'][^\"']*[\"']")

(defn add-lazy-loading [html]
  (str/replace
    html
    #"(?is)<img\b[^>]*>"
    (fn [tag]
      (-> tag
          (str/replace responsive-image-attributes-pattern "")
          (str/replace-first #"(?i)<img\b" "<img loading=\"lazy\" decoding=\"async\"")))))

(defn localize-images! [post-id html]
  (let [clean-html (-> html
                       (str/replace tracking-image-pattern "")
                       (str/replace script-pattern ""))
        sources (->> (re-seq image-source-pattern clean-html)
                     (map second)
                     distinct
                     vec)
        images (->> sources
                    (map-indexed #(download-image! post-id %1 %2))
                    (remove nil?)
                    vec)
        public-paths (into {} (map (juxt :source :public-path) images))
        localized (str/replace
                    clean-html
                    image-source-pattern
                    (fn [[tag source]]
                      (if-let [public-path (get public-paths source)]
                        (str/replace tag source public-path)
                        "<span class=\"missing-media\">Archived image unavailable.</span>")))]
    {:html (-> localized
               add-lazy-loading
               (str/replace #"[\r\n]+" " ")
               str/trim)
     :images images}))

(defn parse-medium-feed [xml-text]
  (let [root (xml/parse-str xml-text)
        channel (child root "channel")]
    (mapv
      (fn [item]
        (let [title (text-content (child item "title"))
              source-url (text-content (child item "link"))
              published-raw (text-content (child item "pubDate"))
              encoded (text-content (child item "encoded"))
              id (medium-post-id source-url)
              localized (localize-images! id encoded)]
          {:id id
           :title (html-safe-title title)
           :published (medium-display-date published-raw)
           :published-raw published-raw
           :source "medium"
           :source-url source-url
           :html (:html localized)
           :images (mapv #(select-keys % [:public-path]) (:images localized))}))
      (child-elements channel "item"))))

(defn parse-wordpress-post [post]
  (let [id (str "wp-" (:id post))
        localized (localize-images! id (get-in post [:content :rendered]))
        published-raw (:date post)]
    {:id id
     :title (html-safe-title (get-in post [:title :rendered]))
     :published (wordpress-display-date published-raw)
     :published-raw published-raw
     :source "wordpress"
     :source-url (:link post)
     :html (:html localized)
     :images (mapv #(select-keys % [:public-path]) (:images localized))}))

(defn parse-wordpress-posts [json-text medium-posts]
  (let [medium-titles (set (map (comp normalized-title :title) medium-posts))]
    (->> (json/parse-string json-text true)
         (remove #(contains? medium-titles
                             (normalized-title (get-in % [:title :rendered]))))
         (mapv parse-wordpress-post))))

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
                 ";; Generated by scripts/import-archive.bb. Do not edit by hand.\n\n"
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
  (let [medium-posts (parse-medium-feed (fetch-text medium-feed-url))
        wordpress-posts (parse-wordpress-posts (fetch-text wordpress-api-url)
                                               medium-posts)
        posts (->> (concat medium-posts wordpress-posts)
                   (sort-by (comp - sort-key :published-raw))
                   vec)]
    (doseq [{:keys [id html]} posts]
      (spit (str posts-directory "/" id ".html") html))
    (spit metadata-path
          (json/generate-string (mapv #(dissoc % :html) posts) {:pretty true}))
    (write-carp-index! posts)
    (println (format "Imported %d posts (%d Medium, %d WordPress) and %d images"
                     (count posts)
                     (count medium-posts)
                     (count wordpress-posts)
                     (reduce + (map #(count (:images %)) posts))))))

(import!)
