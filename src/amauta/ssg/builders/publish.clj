(ns amauta.ssg.builders.publish
  (:require
   [babashka.process :as proc]
   [clojure.string :as str]))

(def ^:private public-branch "public")

(defn- git-out
  [git-dir & args]
  (let [{:keys [exit out]} @(proc/process (into ["git" "-C" git-dir] args)
                                          {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn squash-unpublished!
  "Ensure git-dir's `public` branch has a commit whose tree matches the
  current tip of the branch you actually develop on, squashing any commits
  made since the last publish into one commit dated now. Force-pushes
  `public` to repo-url so the boundary survives across cache clears, then
  repoints git-dir's local branch ref at that (possibly new) public commit
  so every downstream consumer of git-dir - archive extraction, the
  dumb-HTTP git server, the commit browser, and the GitHub mirror -
  transparently serves the squashed history under the branch's real name,
  without that branch's history in Forgejo ever being touched. The first
  time it's called for a repo, `public` is bootstrapped to branch's current
  tip as-is (no squash) - only commits made after that point ever get
  squashed away, so pre-existing history is never collapsed. Each squash
  commit reuses branch's latest commit message rather than a synthetic one."
  [git-dir repo-url author-name author-email]
  (let [branch (git-out git-dir "symbolic-ref" "--short" "HEAD")
        branch-head (git-out git-dir "rev-parse" branch)
        branch-tree (git-out git-dir "rev-parse" (str branch "^{tree}"))
        public-tip (git-out git-dir
                            "rev-parse" "--verify"
                            "-q"
                            (str "refs/heads/" public-branch))
        tip (cond
              (nil? public-tip)
              (do (proc/shell "git"
                              "-C"
                              git-dir
                              "update-ref"
                              (str "refs/heads/" public-branch)
                              branch-head)
                  (proc/shell "git"
                              "-C"
                              git-dir
                              "push"
                              repo-url
                              (str "refs/heads/"  public-branch
                                   ":refs/heads/" public-branch))
                  branch-head)

              (= branch-tree
                 (git-out git-dir "rev-parse" (str public-tip "^{tree}")))
              public-tip

              :else
              (let [now     (str (java.time.OffsetDateTime/now))
                    message (git-out git-dir "log" "-1" "--format=%B" branch)
                    commit  (str/trim
                             (:out
                              @(proc/process
                                ["git" "-C" git-dir "commit-tree" branch-tree
                                 "-p" public-tip "-m" message]
                                {:out       :string
                                 :err       :string
                                 :extra-env {"GIT_AUTHOR_NAME"     author-name
                                             "GIT_AUTHOR_EMAIL"    author-email
                                             "GIT_COMMITTER_NAME"  author-name
                                             "GIT_COMMITTER_EMAIL" author-email
                                             "GIT_AUTHOR_DATE"     now
                                             "GIT_COMMITTER_DATE"  now}})))]
                (proc/shell "git"
                            "-C"
                            git-dir
                            "update-ref"
                            (str "refs/heads/" public-branch)
                            commit)
                (proc/shell "git"
                            "-C"
                            git-dir
                            "push"
                            "--force"
                            repo-url
                            (str "refs/heads/"  public-branch
                                 ":refs/heads/" public-branch))
                commit))]
    (proc/shell "git"
                "-C"
                git-dir
                "update-ref"
                (str "refs/heads/" branch)
                tip)))
