(ns ncr.clj.bot-api
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [org.httpkit.client :as http]
    [babashka.fs :as fs]
    [taoensso.timbre :as log]
    [cheshire.core :as json]
    [ncr.clj.transit :as transit]
    [oksa.core :as oksa]))

(def ^:private DEFAULTS
  {:neckar-url "https://app.neckar.io/"})

(defn make-client [cfg]
  {:cfg (merge DEFAULTS cfg) :cache (atom {})})

(defn login-into [client cluster]
  (assoc client :cluster cluster))

(defn- cache-set
  ([c k v] (cache-set c k v nil))
  ([cache k v expire]
   (swap! cache assoc k [v expire])))

(defn- cache-get [cache k]
  (when-let [[v expire] (@cache k)]
    (if (or (not expire) (= 1 (. expire compareTo (java.time.Instant/now))))
      (do
        (log/debug "cache hit for" k)
        v)
      (let [msg (if expire "expired" "miss")]
        (log/debug "cache" msg "for" k)
        (swap! cache dissoc k)
        nil))))

(defn- cache-flush [cache]
  (reset! cache {}))

(defmacro with-caching [cache k & body]
  `(let [cache# ~cache
         k# ~k
         line# ~(:line (meta &form))]
     (or
       (cache-get cache# k#)
       (let [_# (log/log! :debug :f ["acquiring %s" k#] {:?line line#}) 
             now# (java.time.Instant/now)
             [v# ttl#] (do ~@body)]
         (log/log! :debug :f ["acquired %s, cache ttl: %s" k# ttl#]
           {:?line line#})
         (cache-set cache# k# v# (.plusSeconds now# ttl#))
         v#))))

(defn- url-join [base path]
  (-> base java.net.URL. (java.net.URL. path) .toString))

(defn- http-req [{:keys [cfg]} url opts]
  (let [opts (cond-> (merge
                       {:method :get :url url :timeout 15000}
                       (:http cfg)
                       opts)
               (:body opts)
               (->
                 (update :headers merge {"content-type" "application/json"})
                 (update :body #(when % (json/generate-string %)))))
        {:keys [error body status]} @(http/request opts)]
    (cond
      error
      (throw error)

      (not= status 200)
      (throw (ex-info "bad http status" {:status status :body body}))

      :else
      (json/parse-string body true))))

(defn oidc-config [{:keys [cfg cache] :as client}]
  (with-caching cache :oidc-config
    [(http-req client
      (url-join (get-in cfg [:auth :realm]) ".well-known/openid-configuration")
      {:method :get})
     3600]))

(defn oidc-token [{:keys [cfg cache] :as client}]
  (when-let [{:keys [client-id username password scope]
              :or {scope "openid email profile"}}
             (:auth cfg)]
    (with-caching cache :oidc
      (let [{:keys [token_endpoint]} (oidc-config client)

            {:keys [access_token expires_in]}
            (http-req client token_endpoint
              {:method :post
               :form-params {:client_id client-id
                             :grant_type "password"
                             :scope scope
                             :username username
                             :password password}})]
        [access_token (long (* expires_in 4/5))]))))

(defn fetch-userinfo [{:keys [cfg cache] :as client}]
  (let [{:keys [userinfo_endpoint]} (oidc-config client)
        token (oidc-token client)]
    (http-req client userinfo_endpoint
      {:oauth-token token})))

(defn graphql* [{:keys [cluster cfg] :as client} query & [vars]]
  (http-req client (url-join (:neckar-url cfg) "/api/graphql")
    {:method :post
     :oauth-token (oidc-token client)
     :headers (when cluster {"X-Ncr-Cluster-Slug" cluster})
     :body
     {:query (cond-> query (not (string? query)) oksa/gql)
      :variables vars}}))

(defn graphql [client query & [vars]]
  (let [{:keys [errors] :as resp} (graphql* client query vars)]
    (when errors
      (throw (ex-info "GraphQL Error" {:errors errors})))
    resp))

(def transit< transit/decode)

(def transit> transit/encode)

#_(transit< (transit> [:a :b (bigdec 123)]))

(defn upload-file [client ^java.io.File file]
  (assert (instance? java.io.File file))
  (assert (fs/exists? file))
  (let
    [fname (.getName file)

     args
     {:fileName fname
      :lastModified (-> file
                      fs/last-modified-time
                      .toInstant
                      .toString
                      (str/split #"\.")
                      first)
      :length (fs/size file)}

     _ (log/debug "uploading" args)

     resp
     (graphql client
       [:oksa/mutation
        [[:upload_create
          {:alias :default
           :arguments args}
          [:id :url :params [:name :value]]]]])

     _ (log/debug " [x] created")

     {:keys [id url params]} (get-in resp [:data :default])

     {:keys [error status body] :as resp1}
     @(http/request
        {:url url
         :method :post
         :multipart
         (conj
           (mapv #(set/rename-keys % {:value :content}) params)
           {:name "file" :content file :filename fname})})]

    (log/debug " [x] uploaded")
    (when-not (<= 200 status 299)
      (throw
        (ex-info "bad http response" {:code status :body body :error error})))

    (graphql client [:oksa/mutation [[:upload_confirm {:arguments {:id id}}]]])
    (log/debug " [x] confirmed:" id)

    {:id id}))

(comment
  (def ncr
    (-> "config/config.edn" slurp read-string make-client))

  (def ncr
    (make-client {:neckar-url "https://snapshot.neckar.suprematic.team/"}))

  (oidc-config ncr)

  (fetch-userinfo ncr)

  (oidc-token ncr)

  (def ^:private Q_FIND_CLUSTER
    "query($slug: String) {
      default:cluster_find_by_slug(slug: $slug) {
        id, name
      }
    }")

  (graphql ncr Q_FIND_CLUSTER {:slug "suprematic"})

  (graphql ncr
    [:oksa/query
     [[:cluster_find_by_slug
       {:alias :default
        :arguments {:slug "suprematic"}}
       [:id :name]]]])

  (graphql ncr
    [:oksa/query {:variables [:slug :String]}
     [[:cluster_find_by_slug
       {:arguments {:slug :$slug}}
       [:id :name]]]]
    {:slug "suprematic"})

  (def ncr-s
    (login-into ncr "suprematic"))

  (def ^:private Q_READ_PROFILE
    "query($scope: String!) { profile_account_read(scope: $scope) }")

  (-> ncr-s
    (graphql Q_READ_PROFILE {:scope "rosettings"})
    (get-in [:data :profile_account_read])
    transit<)

  (upload-file ncr-s (io/file "README.md"))

  ;;
  )
