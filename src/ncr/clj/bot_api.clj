(ns ncr.clj.bot-api
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [cognitect.transit :as transit]
    [org.httpkit.client :as http]
    [taoensso.timbre :as log]
    [cheshire.core :as json]))

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

(defn- http-req [base path opts]
  (let [url (.toString (cond-> (java.net.URL. base) path (java.net.URL. path)))
        opts (cond-> (merge {:method :get :url url :timeout 5000} opts)
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

(defn ^{:deprecated "0.2"} vault-token
  "Deprecated"
  [{:keys [cfg cache] :as client}]
  (or
    (cache-get cache :vault-token)
    (let [_ (log/debug "vault token: authenticating") 
          now (java.time.Instant/now)
          vauth (http-req (:vault-url cfg) "/v1/auth/approle/login"
                {:method :post
                 :body (select-keys (:role cfg) [:role_id :secret_id])})

          {:keys [lease_duration client_token]}
          (:auth vauth)]
      (log/info "vault token: acquired")
      (cache-set cache :vault-token client_token
        (.plusSeconds now (/ lease_duration 2)))
      client_token)))

(defn ^{:deprecated "0.2"} vault-oidc-token
  "Deprecated"
  [{:keys [cfg cache] :as client}]
  (or
    (cache-get cache :vault-oidc)
    (let [now (java.time.Instant/now)
          vtoken (vault-token client)
          _ (log/debug "vault oidc requesting") 
          role-path (str "/v1/identity/oidc/token/" (get-in cfg [:role :name]))
          voidc (http-req (:vault-url cfg) role-path
                  {:headers {"x-vault-token" vtoken}})
          {:keys [ttl token]} (:data voidc)]
      (log/info "vault oidc acquired")
      (cache-set cache :vault-oidc token (.plusSeconds now (/ ttl 2)))
      token)))

(defn oidc-config [{:keys [cfg cache] :as client}]
  (or
    (cache-get cache :oidc-config)
    (let [_ (log/debug "oidc-config: requesting") 
          realm (get-in cfg [:auth :realm])
          now (java.time.Instant/now)
          oidc-cfg (http-req realm ".well-known/openid-configuration"
                     {:method :get})]
      (log/info "oidc-config: acquired")
      (cache-set cache :oidc-config oidc-cfg (.plusSeconds now 3600))
      oidc-cfg)))

(defn oidc-token [{:keys [cfg cache] :as client}]
  (or
    (cache-get cache :oidc)
    (let [now (java.time.Instant/now)
          {:keys [token_endpoint]} (oidc-config client)
          {:keys [client-id username password]} (:auth cfg)
          _ (log/debug "oidc-token: requesting") 
          {:keys [access_token expires_in]}
          (http-req token_endpoint nil
            {:method :post
             :form-params {:client_id client-id
                           :grant_type "password"
                           :scope "openid email profile"
                           :username username
                           :password password}})]
      (log/info "oidc-token: acquired, expires_in:" expires_in)
      (cache-set cache :oidc access_token
        (.plusSeconds now (long (* expires_in 4/5))))
      access_token)))

(defn fetch-userinfo [{:keys [cfg cache] :as client}]
  (let [{:keys [userinfo_endpoint]} (oidc-config client)
        token (oidc-token client)]
    (http-req userinfo_endpoint nil
      {:oauth-token token})))

(defn graphql [{:keys [cluster] :as client} query & [vars]]
  (let [headers
        (cond-> {"authorization" (str "Bearer " (oidc-token client))}
          cluster (assoc "X-Ncr-Cluster-Slug" cluster))]
    (http-req (get-in client [:cfg :neckar-url]) "/api/graphql"
      {:method :post
       :headers headers
       :body {:query query :variables vars}})))

(defn transit< [in]
  (let [in (java.io.ByteArrayInputStream. (.getBytes in))]
    (transit/read (transit/reader in :json))))

(defn transit> [in]
  (let
    [out (java.io.ByteArrayOutputStream.)
     writer (transit/writer out :json)]
    (transit/write writer in)
    (.toString out)))

#_(transit< (transit> [:a :b]))

(comment
  (def ncr
    (-> "config/config.edn" slurp read-string make-client))

  (def ncr
    (-> "config/mail-import.dev.edn" slurp read-string make-client))

  (def ncr
    (-> "config/textract.dev.edn" slurp read-string make-client))

  (def ncr
    (-> "config/probebot.dev.edn" slurp read-string make-client))

  (oidc-config ncr)

  (fetch-userinfo ncr)

  (cache-flush (:cache ncr))

  (oidc-token ncr)

  (def ^:private Q_FIND_CLUSTER
    "query($slug: String) {
       default:cluster_find_by_slug(slug: $slug) {
         id, name
       }
     }")

  (graphql ncr Q_FIND_CLUSTER {:slug "suprematic"})

  (def ncr-s
    (login-into ncr "suprematic"))

  (def ^:private Q_READ_PROFILE
    "query($scope: String!) {
        profile_account_read(scope: $scope)
     }")

  (-> ncr-s
    (graphql Q_READ_PROFILE {:scope "robot/settings"})
    (get-in [:data :profile_account_read])
    transit<)

  ;;
  )
