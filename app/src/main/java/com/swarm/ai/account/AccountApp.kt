package com.swarm.ai.account

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AccountApp(client:AccountClient, callback:Uri?, onCompanion:()->Unit) {
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("Chat") }
    var chats by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var current by remember { mutableStateOf<JSONObject?>(null) }
    var prompt by remember { mutableStateOf("") }
    var entitlement by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var modelProfile by remember { mutableStateOf("swe") }
    var speed by remember { mutableStateOf("balanced") }
    val scope=rememberCoroutineScope()
    suspend fun refresh(){profile=client.profile();if(profile!=null){val array=client.call("conversations").getJSONArray("conversations");chats=(0 until array.length()).map { array.getJSONObject(it) };entitlement=client.call("account/entitlements");val account=entitlement!!.getJSONObject("account");modelProfile=account.getString("profile");speed=account.getString("speed")}}
    fun action(work:suspend ()->Unit){scope.launch { loading=true;error=null;try{work()}catch(e:Exception){error=if(e is java.io.IOException)"Network unavailable. Check your connection and retry." else e.message ?: "Request failed. Please retry.";if(runCatching { client.profile() }.getOrNull()==null)profile=null}finally{loading=false} }}
    LaunchedEffect(callback){loading=true;try{if(callback!=null)client.callback(callback);refresh()}catch(e:Exception){error=e.message ?: "Could not connect. Please retry."}finally{loading=false}}
    BackHandler(current!=null || page!="Chat"){current=null;page="Chat"}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.safeDrawingPadding().fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Image(painterResource(com.swarm.ai.R.drawable.swarm_wordmark),contentDescription="SWARM",modifier=Modifier.width(150.dp).height(35.dp))
        if(loading){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Connecting to SWARM…",fontSize=13.sp)}
        error?.let { Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={action{refresh()}},enabled=!loading){Text("Retry") } }
        if(profile==null){Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Text("Welcome back",style=MaterialTheme.typography.headlineMedium);Text("Sign in to your SWARM workspace");Spacer(Modifier.height(24.dp));Button(onClick={try{client.begin()}catch(e:Exception){error=e.message}},enabled=!loading){Text("Continue with Google or email")};Text("Sign in or create an account on the secure SWARM website.",modifier=Modifier.padding(16.dp));TextButton(onClick=onCompanion){Text("Connect to my desktop instead")};Text("ONE MISSION. MANY MINDS.",fontSize=11.sp)}}
        else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={page="Chat"}){Text("Chat")};TextButton(onClick={page="Settings";action{refresh();val rows=client.call("billing/history").getJSONArray("transactions");history=(0 until rows.length()).map { rows.getJSONObject(it) }}}){Text("Settings")};TextButton(onClick=onCompanion){Text("Desktop")}}
            if(page=="Settings"){
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(profile?.optString("name")?.takeUnless { it=="null" } ?: "Your SWARM account",style=MaterialTheme.typography.titleLarge);Text(profile?.optString("email").orEmpty())
                    entitlement?.let { usage ->
                        Text("Plan: "+usage.getJSONObject("plan").getString("id").uppercase())
                        listOf("builds","chats").forEach { kind -> val quota=usage.getJSONObject(kind);Text("$kind: ${quota.getInt("used")} / ${quota.opt("limit").takeUnless { it==JSONObject.NULL } ?: "awaiting configuration"}") }
                        Text("Allowance resets: "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(usage.getLong("resetsAt"))))
                        val expiry=usage.getJSONObject("account").getLong("pro_until");if(expiry>0)Text("Pro expiration: "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expiry)))
                    }
                    Text("Model profile");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("swe","flash","premium","max").forEach { value ->FilterChip(selected=modelProfile==value,onClick={modelProfile=value},label={Text(value)},enabled=!loading) }}
                    Text("Speed / quality");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("fast","balanced","quality").forEach { value ->FilterChip(selected=speed==value,onClick={speed=value},label={Text(value)},enabled=!loading) }}
                    Button(enabled=!loading&&entitlement!=null,onClick={action{client.call("account/model-preferences",JSONObject().put("profile",modelProfile).put("speed",speed));refresh()}}){Text("Save preferences")}
                    Text("SWARM manages AI access. Profiles require their verified Pro or Max entitlement and an available configured model. No provider key is needed.")
                    Button(enabled=!loading,onClick={try{client.openBilling()}catch(e:Exception){error=e.message}}){Text("Plans and billing on website")}
                    Text("Sign into the same SWARM account on the website. After verified payment, refresh your account here.")
                    Text("Transaction history");if(history.isEmpty())Text("No transactions yet.")
                    history.forEach { order ->Text("${order.optString("id")} · ${order.optString("status")} · ₹${order.optInt("amount")/100.0}") }
                    TextButton(onClick={action{refresh()}},enabled=!loading){Text("Refresh account")}
                    TextButton(onClick={action{try{client.logout()}finally{profile=null;current=null;chats=emptyList();entitlement=null;history=emptyList()}}},enabled=!loading){Text("Sign out")}
                }
            } else if(current==null){
                Button(onClick={action{current=client.call("conversations",JSONObject().put("title","New chat"));refresh()}},enabled=!loading){Text("New chat")}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){chats.forEach { chat ->TextButton(onClick={action{current=client.call("conversations/"+chat.getString("id"))}},enabled=!loading){Text(chat.optString("title"))} } };if(chats.isEmpty()&&!loading)Text("Start your first conversation.")
            } else {
                Text(current?.optString("title").orEmpty(),style=MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){val messages=current?.optJSONArray("messages");if(messages!=null)for(i in 0 until messages.length()){val m=messages.getJSONObject(i);Text(m.optString("role").uppercase(),fontSize=11.sp);Text(m.optString("content"))}}
                OutlinedTextField(value=prompt,onValueChange={prompt=it},label={Text("Message SWARM")},modifier=Modifier.fillMaxWidth(),enabled=!loading)
                Button(enabled=!loading&&prompt.isNotBlank(),onClick={val text=prompt;action{current=client.send(current!!.getString("id"),text);prompt="";refresh()}}){Text("Send")}
            }
        }
    }}
}
