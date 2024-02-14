(ns ncr.clj.transit
  (:require
   [cognitect.transit :as transit]
   [cljc.java-time.local-date]
   [cljc.java-time.local-time]
   [cljc.java-time.local-date-time]
   [cljc.java-time.zoned-date-time]
   #?(:cljs
      ["bignumber.js" :as big])
   [tick.core :as t])
  (:import
   #?(:clj
      [java.math BigDecimal])))

#?(:cljs
   (def ^:private BigDecimal big/BigNumber))

#?(:cljs
   (defn- bigdec [s]
     (big/BigNumber. s)))

(defn- write-handler
  ([tag] (write-handler tag str))
  ([tag rep-fn]
   (transit/write-handler #?(:clj tag :cljs (constantly tag)) rep-fn)))

(def write-handlers
  {java.time.LocalDateTime (write-handler "t-ldt")
   java.time.LocalDate (write-handler "t-ld")
   java.time.LocalTime (write-handler "t-lt")
   java.time.ZonedDateTime (write-handler "t-zdt")
   BigDecimal (write-handler "bd")})

(defn- read-handler [rep-fn]
  (let [rep-fn
        #?(:clj rep-fn
           :cljs (fn [s _t] (rep-fn s)))]
    (transit/read-handler rep-fn)))

(def read-handlers
  {"t-ldt" (read-handler t/date-time)
   "t-ld" (read-handler t/date)
   "t-lt" (read-handler t/time)
   "t-zdt" (read-handler t/zoned-date-time)
   "bd" (read-handler bigdec)})

#?(:clj
   (do
     (defn encode [v]
       (let [out (java.io.ByteArrayOutputStream.)]
         (-> out
           (transit/writer :json {:handlers write-handlers})
           (transit/write v))
         (.toString out)))

     (defn decode [s]
       (-> s
         .getBytes
         java.io.ByteArrayInputStream.
         (transit/reader :json {:handlers read-handlers})
         transit/read))))

#?(:cljs
   (do
     (defn encode [v]
       (transit/write (transit/writer :json {:handlers write-handlers}) v))

     (defn decode [s]
       (transit/read (transit/reader :json {:handlers read-handlers}) s))))

(comment
  (def ^:private x1 [:a :b :c])
  (def ^:private x2 [:a (bigdec "1.2345") :c])
  (def ^:private x3 [:a (t/date-time) :c])

  (-> x1 encode decode)
  (-> x2 encode decode)
  (-> x3 encode decode)
  ;;
  )

