(ns amauta.ssg.builders.publish
  (:require
   [babashka.process :as proc]
   [clojure.string :as str]))

(def ^:private public-branch "public")
(def ^:private source-tag "public-source")

(defn- git-out
  [git-dir & args]
  (let [{:keys [exit out]} @(proc/process (into ["git" "-C" git-dir] args)
                                          {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn- ancestor?
  [git-dir ancestor descendant]
  (-> @(proc/process ["git" "-C" git-dir "merge-base" "--is-ancestor"
                      ancestor descendant]
                     {:out :string :err :string})
      :exit
      zero?))

(defn- redate-commit!
  "Build a copy of sha as a new commit with the same tree, message, and
  author/committer identity, but with author/committer date set to now."
  [git-dir sha parent now]
  (let [[tree author-name author-email committer-name committer-email]
        (str/split (git-out git-dir
                            "log"                                     "-1"
                            "--format=%T%x1f%an%x1f%ae%x1f%cn%x1f%ce" sha)
                   #"\x1f")
        message (git-out git-dir "log" "-1" "--format=%B" sha)
        args (concat ["git" "-C" git-dir "commit-tree" tree]
                     (when parent ["-p" parent])
                     ["-m" message])]
    (str/trim
     (:out @(proc/process args
                          {:out       :string
                           :err       :string
                           :extra-env {"GIT_AUTHOR_NAME"     author-name
                                       "GIT_AUTHOR_EMAIL"    author-email
                                       "GIT_COMMITTER_NAME"  committer-name
                                       "GIT_COMMITTER_EMAIL" committer-email
                                       "GIT_AUTHOR_DATE"     now
                                       "GIT_COMMITTER_DATE"  now}})))))

(defn- reset-public!
  "(Re)establish `public` and public-source as an exact, unrewritten copy
  of branch's current state; used both for a repo's first-ever sync and
  to recover after branch has been rebased past the previously recorded
  source commit."
  [git-dir repo-url branch branch-head]
  (proc/shell "git"
              "-C"
              git-dir
              "update-ref"
              (str "refs/heads/" public-branch)
              branch-head)
  (proc/shell "git" "-C" git-dir "tag" "-f" source-tag branch-head)
  (proc/shell "git"
              "-C"
              git-dir
              "push"
              "--force"
              repo-url
              (str "refs/heads/" public-branch ":refs/heads/" public-branch)
              (str "refs/tags/" source-tag ":refs/tags/" source-tag))
  (proc/shell "git"
              "-C"
              git-dir
              "update-ref"
              (str "refs/heads/" branch)
              branch-head))

(defn sync-public!
  "Ensure git-dir's `public` branch has a redated copy of every commit on
  the branch you actually develop on. Each new commit's tree, message, and
  author/committer identity are preserved exactly; only its date is reset
  to now, so nothing reveals when it was really made. All commits redated
  in the same run share one timestamp.

  The first time it's called for a repo, and any time branch turns out to
  have been rebased past the commit recorded as last synced (detected via
  the `public-source` tag no longer being an ancestor of branch), `public`
  is reset to an exact, unrewritten copy of branch's current state, so
  pre-existing/rebased-in history, its real dates, and any real signatures
  are left untouched, and only commits made after that point ever get
  redated. This is logged, since it means the previously published window
  was discarded rather than silently (mis)reconciled.

  Force-pushes `public` and public-source to repo-url so the boundary
  survives across cache clears, then repoints git-dir's local branch ref
  at public's tip so every downstream consumer of git-dir (archive
  extraction, the dumb-HTTP git server, the commit browser, and the
  GitHub mirror) transparently serves the redated history under the
  branch's real name, without that branch's history in Forgejo ever
  being touched."
  [git-dir repo-url]
  (let [branch      (git-out git-dir "symbolic-ref" "--short" "HEAD")
        branch-head (git-out git-dir "rev-parse" branch)
        public-tip  (git-out git-dir
                             "rev-parse" "--verify"
                             "-q"
                             (str "refs/heads/" public-branch))
        source-tip  (git-out git-dir
                             "rev-parse" "--verify"
                             "-q"
                             (str "refs/tags/" source-tag))]
    (cond
      (or (nil? public-tip) (nil? source-tip))
      (reset-public! git-dir repo-url branch branch-head)

      (not (ancestor? git-dir source-tip branch))
      (do (println "public-source no longer reachable from"
                   branch
                   "- branch was likely rebased, resetting `public` baseline")
          (reset-public! git-dir repo-url branch branch-head))

      :else
      (let [new-shas (remove str/blank?
                             (str/split-lines
                              (git-out git-dir
                                       "rev-list"
                                       "--reverse"
                                       (str source-tip ".." branch))))]
        (if (empty? new-shas)
          (proc/shell "git"
                      "-C"
                      git-dir
                      "update-ref"
                      (str "refs/heads/" branch)
                      public-tip)
          (let [now (str (java.time.OffsetDateTime/now))
                tip (reduce (fn [parent sha]
                              (redate-commit! git-dir sha parent now))
                            public-tip
                            new-shas)]
            (proc/shell "git"
                        "-C"
                        git-dir
                        "update-ref"
                        (str "refs/heads/" public-branch)
                        tip)
            (proc/shell "git" "-C" git-dir "tag" "-f" source-tag branch-head)
            (proc/shell "git"
                        "-C"
                        git-dir
                        "push"
                        "--force"
                        repo-url
                        (str "refs/heads/"  public-branch
                             ":refs/heads/" public-branch)
                        (str "refs/tags/" source-tag ":refs/tags/" source-tag))
            (proc/shell "git"
                        "-C"
                        git-dir
                        "update-ref"
                        (str "refs/heads/" branch)
                        tip)))))))
