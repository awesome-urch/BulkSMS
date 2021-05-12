package spartons.com.prosmssenderapp.workers

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import spartons.com.prosmssenderapp.BuildConfig

interface APIService {

//    @POST("/api/v1/create") ///2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/Messages.json
//    suspend fun sendSms(@Body requestBody: RequestBody): Response<ResponseBody>

//    @Headers({
//        "Content-Type: application/json",
//        "Authorization: Basic KzY1OTY0MjA4NTQ6MTExMTE="
//    }.toString())
    @POST("/2010-04-01/Accounts/${BuildConfig.ACCOUNT_SID}/Messages.json") ///2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/Messages.json
    suspend fun sendSms(@Body requestBody: RequestBody): Response<ResponseBody>

    /*@POST("/2010-04-01/Accounts/${BuildConfig.ACCOUNT_SID}/Messages.json") ///2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/Messages.json
    @FormUrlEncoded
    suspend fun pushSms(@Field requestBody: RequestBody): Response<ResponseBody>*/

    @POST("/2010-04-01/Accounts/${BuildConfig.ACCOUNT_SID}/Messages.json")
    @FormUrlEncoded
    suspend fun pushSms(
        @Field("From") From: String,
        @Field("To") To: String,
        @Field("Body") Body: String
    ): Response<ResponseBody>

}