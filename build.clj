(ns build
  (:refer-clojure :exclude [test])
  (:require
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]))

(def lib 'io.github.suprematic/ncr.clj.bot-api)
#_(def version "0.1.0-SNAPSHOT")
; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "0.3.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (println "\nCleaning target...")
  (b/delete {:path "target"}))

(defn run-tests [_]
  (let [{:keys [exit]} (b/process {:command-args ["clojure" "-X:test"]})]
    (assert (= exit 0))))

(defn jar [_]
  (println (format "\nWriting pom for v%s ..." version))
  (b/write-pom {:class-dir class-dir
                :src-pom "template/pom.xml"
                :scm {:tag (str "v" version)}
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]})
  (println "\nCopying to target...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (println "\nBuilding jar...")
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn ci [opts]
  (run-tests opts)
  (clean opts)
  (jar opts))

(defn deploy [_]
  (println "\nDeploying to clojars...")
  (deploy/deploy
    {:installer :remote
     :artifact (b/resolve-path jar-file)
     :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
