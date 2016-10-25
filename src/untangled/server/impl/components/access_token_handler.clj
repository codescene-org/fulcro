(ns untangled.server.impl.components.access-token-handler
  (:require [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.util.response :refer [get-header]]
            [clojure.string :refer [split]]
            [clojure.walk :as walk]
            [com.stuartsierra.component :as component]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [untangled.server.impl.jwt-validation :refer :all]
            [bidi.bidi :as bidi]))

(defn- add-claims-to-request
  "Adds a :user to the request, which is a map of claims."
  [req token options]
  (assoc req :user ((:claims-transform options) (:claims token))))

(defn- default-claims-transform [claims]
  claims)

(defn- get-token-from-bearer [str]
  "Gets access token from the Bearer string."
  (let [[bearer token] (split str #" ")]
    (if (and token (= bearer "Bearer")) token nil)))

(defn- get-token [request]
  (when-let [token (or
                     (some-> request :headers (get "authorization") (get-token-from-bearer))
                     (get-in request [:params "openid/access-token"])
                     (get-in request [:form-params "access_token"])
                     (get-in request [:session :access-token])
                     (get-in request [:cookies "access_token" :value]) ;; Run cookie last in case token is new.
                     )]
    (read-token token)))

(defn- missing-token? [token]
  (nil? token))

(defn- missing-sub? [token]
  (nil? (:sub (:claims token))))

(defn- missing-client-id? [token]
  (nil? (:client_id (:claims token))))

(defn- fail-with [token message]
  (log/debug "Token: " (:claims token) " Failed because: " message)
  false)

(defn- valid-token? [token {:keys [public-keys issuer audience grace-period-minutes]}]
  (cond
    (missing-token? token) (fail-with token "Token is missing.")
    (not (valid-signature? token public-keys)) (fail-with token "Invalid signature.")
    (not (valid-issuer? token issuer)) (fail-with token "Invalid issuer.")
    (not (valid-expire? token grace-period-minutes)) (fail-with token "Expired token.")
    (not (valid-audience? token audience)) (fail-with token (str "Invalid audience. cfg-aud<" audience ">."))

    (and (missing-sub? token) (missing-client-id? token))
    (fail-with token (if (missing-sub? token) "Missing subject." "Missing client id."))

    :else true))

(def default-options
  {:authority            ""
   :audience             "api"
   :grace-period-minutes 1
   :claims-transform     default-claims-transform})

(defn unsecured-route? [{:keys [uri request-method] :as request} {:keys [unsecured-routes]}]
  (bidi/match-route
    ["" (merge {#"/[^/]*\.[^/]*" :ok "/" :ok}
          unsecured-routes)]
    uri :request-method request-method))

(defn wrap-access-token
  "Middleware that validates the request for a JWT access-token that are issued by
  an OpenID Connect server.  Validation rules include access-token signiture, issuer and
  audience.  The request wrapper also calls a specified function after validation to allow for
  claims transformation before associating the claims with the request.
  A :claims-principle map will be associated with the session after the claims
  claims transformation function is called.

  :issuer - a string that contains the issuer of the OpenID Connect server - required
  :public-key - a string that contains the public key used to validate the signiature - required
  :audience - a string that contains the Audience expected for this resource - required
  :grace-period-minutes - number of minutes token is allowed after token expiration time - optional (default 1)
  :claims-transform - function that is handle the token claims and must return
    transformed claims - optional"
  {:arglists '([options handler])}
  [options handler]
  (let [merged-options (merge default-options options)]
    (fn [{:as request :keys [uri]}]
      (if (unsecured-route? request merged-options)
        (do (log/trace "Unsecured route: " uri)
            (handler request))
        (let [_     (log/trace "Securing route: " uri)
              token (get-token request)]
          (if-not (valid-token? token merged-options)
            (let [{:keys [invalid-token-handler]} merged-options]
              (if-let [_ok-anyway? ((or invalid-token-handler (constantly false)) request)]
                (handler request)
                {:status 401}))
            (handler (add-claims-to-request request token merged-options))))))))

(defn validate-unsecured-route-handlers! [unsecured-routes]
  (assert (map? unsecured-routes) (str "unsecured-routes was not a map: " unsecured-routes))
  (walk/prewalk #(do (when (and (map-entry? %)
                             (not (coll? (val %))))
                       (assert (= :ok (val %))
                         (str "unsecured-routes handler <" % "> was not :ok")))
                     %)
    unsecured-routes)
  true)

(defrecord OpenIdConfig [config]
  component/Lifecycle
  (start [this]
    (log/info "Starting openid config download")
    (http/with-connection-pool {:timeout 5 :threads 2}
      (let [openid-config     (-> config :value :openid)
            _                 (validate-unsecured-route-handlers! (:unsecured-routes openid-config))
            authority         (:authority openid-config)
            discovery-doc-url (str authority "/.well-known/openid-configuration")]
        (if-let [discovery-doc (-> discovery-doc-url http/get :body json/read-str)]
          (let [issuer          (get discovery-doc "issuer")
                public-keys-url (get discovery-doc "jwks_uri")
                public-keys     (-> public-keys-url http/get :body json/read-str (get "keys"))
                public-keys'    (public-keys-from-jwks public-keys)
                openid-config'  (assoc openid-config :public-keys public-keys' :issuer issuer)]
            (assoc this :value openid-config'
              :middleware identity))
          (throw (ex-info "AccessTokenHandler: Error retrieving discovery document." {}))))))
  (stop [this] this))

(defrecord AccessTokenHandler [openid-config]
  component/Lifecycle
  (start [this]
    (log/info "Starting Access Token Handler")
    (assoc this :middleware
      (fn [h]
        (->> h
          (wrap-access-token (:value openid-config))
          ((:middleware openid-config))))))
  (stop [this] this))
