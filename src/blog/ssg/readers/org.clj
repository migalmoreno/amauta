(ns blog.ssg.readers.org
  "Org-mode reader: renders .org files to HTML via an Emacs subprocess.

  Respects these environment variables:
    HAUNT_ORG_READER_CACHE_DIR          - cache directory (default: .org-mode-reader-cache)
    HAUNT_ORG_READER_DISABLE_CACHE      - disable caching when set
    HAUNT_ORG_READER_EMACS_PREAMBLE     - path to an elisp file loaded before export
    HAUNT_ORG_READER_USE_EMACSCLIENT    - use emacsclient instead of emacs --batch
    HAUNT_ORG_READER_EMACS_DAEMON_NAME  - emacsclient daemon socket name (default: blog-build)"
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [blog.ssg.post :as post])
  (:import
   [java.io File]
   [java.security MessageDigest]))

(def ^:private config
  {:cache-dir       (or (System/getenv "HAUNT_ORG_READER_CACHE_DIR")
                        ".org-mode-reader-cache")
   :cache-enabled?  (not (System/getenv "HAUNT_ORG_READER_DISABLE_CACHE"))
   :preamble        (System/getenv "HAUNT_ORG_READER_EMACS_PREAMBLE")
   :use-emacsclient (boolean (System/getenv "HAUNT_ORG_READER_USE_EMACSCLIENT"))
   :daemon-name     (or (System/getenv "HAUNT_ORG_READER_EMACS_DAEMON_NAME")
                        "blog-build")})

(defn- escape-path
  [path]
  (-> path
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- with-preamble
  [form]
  (if-let [p (:preamble config)]
    (str "(progn (load-file \"" (escape-path p) "\") " form ")")
    form))

(defn- render-form
  [file-path tmp-path]
  (with-preamble
   (str "(save-excursion"
        " (require 'htmlize)"
        " (setq org-html-htmlize-output-type 'css)"
        " (let ((enable-local-variables :all))"
        "   (set-buffer (find-file-noselect \""
        (escape-path file-path)
        "\")))"
        " (setq-local org-export-filter-latex-fragment-functions"
        "   (list (lambda (data backend channel)"
        "           (org-html-encode-plain-text data))))"
        " (let ((result (org-export-as 'html nil nil t)))"
        "   (with-temp-buffer"
        "     (insert result)"
        "     (write-region (point-min) (point-max) \""
        (escape-path tmp-path)
        "\"))))")))

(defn- elisp-string-list
  [strings]
  (str "(" (str/join " " (map #(str "\"" % "\"") strings)) ")"))

(defn- metadata-form
  [file-path keys]
  (with-preamble
   (str "(save-excursion"
        " (let ((enable-local-variables :all))"
        "   (set-buffer (find-file-noselect \""
        (escape-path file-path)
        "\")))"
        " (org-collect-keywords '"
        (elisp-string-list keys)
        "))")))

(defn- daemon-running?
  []
  (and (:use-emacsclient config)
       (zero? (:exit (proc/sh ["emacsclient" "--alternate-editor" "false"
                               "-s" (:daemon-name config) "-e" "t"])))))

(defn stop-daemon!
  []
  (when (daemon-running?)
    (proc/sh ["emacsclient" "-s" (:daemon-name config) "-e" "(kill-emacs)"])))

(defonce ^:private _shutdown-hook
  (let [hook (Thread. stop-daemon!)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn- ensure-daemon!
  []
  (when (and (:use-emacsclient config) (not (daemon-running?)))
    (proc/process ["emacs" (str "--daemon=" (:daemon-name config)) "-Q"])
    (Thread/sleep 2000)))

(def ^:private emacs-timeout-ms 30000)

(defn- run-emacs
  [form-str]
  (let [cmd    (if (:use-emacsclient config)
                 ["emacsclient" "--alternate-editor" "false"
                  "-s" (:daemon-name config) "-e" form-str]
                 ["emacs" "--batch" "--eval" form-str])
        p      (proc/process cmd {:out :string :err :string})
        result (deref p emacs-timeout-ms ::timeout)]
    (when (= result ::timeout)
      (.destroyForcibly ^java.lang.Process (:proc p))
      (throw (ex-info (str "Emacs subprocess timed out after "
                           (/ emacs-timeout-ms 1000)
                           "s")
                      {})))
    (let [{:keys [out err exit]} result]
      (when-not (zero? exit)
        (throw (ex-info (str "Emacs subprocess failed\n" err)
                        {:exit exit :stderr err})))
      out)))

(defn- md5
  [^File file]
  (let [digest (MessageDigest/getInstance "MD5")
        bytes  (.readAllBytes (io/input-stream file))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) (.digest digest bytes)))))

(defn- cache-file
  [hash suffix]
  (io/file (:cache-dir config) (str hash "-" suffix)))

(defn- read-cache
  "Return [raw-metadata-map html-string] from cache, or nil on miss."
  [hash]
  (let [meta-f (cache-file hash "metadata")
        html-f (cache-file hash "content")]
    (when (and (.exists meta-f) (.exists html-f))
      [(read-string (slurp meta-f)) (slurp html-f)])))

(defn- write-cache
  [hash metadata-raw html]
  (let [dir (io/file (:cache-dir config))]
    (when-not (.exists dir) (.mkdirs dir)))
  (spit (cache-file hash "metadata") (pr-str metadata-raw))
  (spit (cache-file hash "content") html))

(def ^:private default-extra-keys
  ["CROSSPOST" "SCRIPTS" "META-TAGS" "LICENSE" "SYNOPSIS" "SOURCE-DIR"])

(defn- collect-metadata-raw
  "Call Emacs to collect org keywords; returns a map of keyword->string."
  [^File file extra-keys]
  (let [all-keys    (into ["TITLE" "DATE" "TAGS"] extra-keys)
        form        (metadata-form (.getAbsolutePath file) all-keys)
        form-to-run (if (:use-emacsclient config) form (str "(print " form ")"))
        result      (str/trim (run-emacs form-to-run))
        pairs       (read-string result)]
    (into {} (map (fn [[k v]] [(keyword (str/lower-case k)) v]) pairs))))

(defn- parse-metadata
  "Convert raw string metadata into typed values (dates, tag lists)."
  [raw]
  (cond-> raw
    (:date raw) (update :date post/parse-org-date)
    (:tags raw) (update :tags #(str/split % #"\s+"))))

(defn- render-org
  [^File file]
  (let [tmp (File/createTempFile "ssg-org-" ".html")]
    (try
      (run-emacs (render-form (.getAbsolutePath file) (.getAbsolutePath tmp)))
      (str "<div>" (slurp tmp) "</div>")
      (finally (.delete tmp)))))

(defn read-org-post
  "Read an org-mode FILE and return [metadata content]. Results are
  optionally cached by file content hash."
  [^File file _posts-root]
  (ensure-daemon!)
  (let [hash   (md5 file)
        cached (when (:cache-enabled? config) (read-cache hash))]
    (if cached
      [(parse-metadata (first cached)) (second cached)]
      (let [metadata-raw (collect-metadata-raw file default-extra-keys)
            html         (render-org file)]
        (when (:cache-enabled? config)
          (write-cache hash metadata-raw html))
        [(parse-metadata metadata-raw) html]))))

(def org-reader
  "A reader map for .org files."
  {:extensions #{"org"}
   :read-fn    read-org-post})
