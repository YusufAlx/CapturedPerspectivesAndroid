package com.capturedperspectives.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(17, 18, 15)
    private val card = Color.rgb(31, 32, 28)
    private val cream = Color.rgb(245, 240, 231)
    private val muted = Color.rgb(176, 170, 157)
    private val gold = Color.rgb(213, 168, 114)
    private val pool = Executors.newFixedThreadPool(3)
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var content: LinearLayout
    private var category = "All"
    private var registering = false
    private var portfolioData: List<PortfolioItem> = samplePortfolio()
    data class PortfolioItem(val title: String, val type: String, val imageId: String, val description: String = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        try {
            if (FirebaseApp.initializeApp(this) == null) {
                showStartupError("Firebase is not configured. Check that app/google-services.json is present and belongs to com.capturedperspectives.app.")
                return
            }
            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()
            if (auth.currentUser == null) showAuth(false) else { seedDemoCollections(); showShell("portfolio") }
        } catch (error: Exception) {
            showStartupError("The app could not initialize Firebase. ${error.localizedMessage ?: "Check your Firebase setup and try again."}")
        }
    }

    private fun showStartupError(message: String) {
        val root = baseRoot()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(26), dp(30), dp(26), dp(30)) }
        panel.addView(text("CAPTURED PERSPECTIVES", 12, gold, true)); panel.addView(space(20))
        panel.addView(text("We couldn't start the app.", 30, cream, true)); panel.addView(space(12))
        panel.addView(text(message, 15, muted)); panel.addView(space(22))
        panel.addView(actionButton("Try again") { recreate() })
        root.addView(panel, LinearLayout.LayoutParams(-1, -1)); setContentView(root)
    }

    private fun showAuth(register: Boolean) {
        registering = register
        val root = baseRoot()
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(34), dp(26), dp(30)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        content.addView(text("CAPTURED PERSPECTIVES", 12, gold, true)); content.addView(space(25))
        content.addView(text(if (register) "Create your\naccount." else "Welcome\nback.", 36, cream, true))
        content.addView(space(10)); content.addView(text("Your photography stories, all in one place.", 15, muted)); content.addView(space(28))
        val name = if (register) input("Your name") else null
        if (name != null) { content.addView(name, fieldParams(54)); content.addView(space(13)) }
        val email = input("Email address").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val password = input("Password").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        content.addView(email, fieldParams(54)); content.addView(space(13)); content.addView(password, fieldParams(54)); content.addView(space(18))
        content.addView(actionButton(if (register) "Create account" else "Log in") {
            val emailValue = email.text.toString().trim(); val pass = password.text.toString()
            val validation = InputValidation.credentials(emailValue, pass, if (register) name?.text.toString().trim() else "")
            if (validation != null) toast(validation)
            else if (register) auth.createUserWithEmailAndPassword(emailValue, pass).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser!!
                    user.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name?.text.toString().trim()).build())
                    saveProfile(name?.text.toString().trim(), emailValue)
                    seedDemoCollections()
                    showShell("portfolio")
                } else toast(authError(task.exception))
            } else auth.signInWithEmailAndPassword(emailValue, pass).addOnCompleteListener { task ->
                if (task.isSuccessful) { seedDemoCollections(); showShell("portfolio") } else toast(authError(task.exception))
            }
        })
        content.addView(space(13)); content.addView(outlineButton("Continue with Google") { googleSignIn() })
        content.addView(space(18))
        content.addView(text(if (register) "Already have an account? Log in" else "New here? Create an account", 14, gold).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9)); setOnClickListener { showAuth(!register) }
        })
        if (!register) content.addView(text("Forgot password? Send reset email", 13, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener {
                val address = email.text.toString().trim()
                if (!InputValidation.isValidEmail(address)) toast("Enter your email address first.")
                else auth.sendPasswordResetEmail(address).addOnSuccessListener { toast("Password reset email sent.") }.addOnFailureListener { toast(authError(it)) }
            }
        })
        content.addView(space(22)); content.addView(text("By continuing, your account is securely managed by Firebase Authentication.", 12, muted))
    }

    private fun googleSignIn() {
        try {
            val option = GetGoogleIdOption.Builder().setServerClientId(getString(R.string.default_web_client_id)).setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val callbackExecutor = java.util.concurrent.Executor { command -> runOnUiThread(command) }
            CredentialManager.create(this).getCredentialAsync(this, request, CancellationSignal(), callbackExecutor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val credential = result.credential
                        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            try {
                                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener { task ->
                                    if (task.isSuccessful) { seedDemoCollections(); showShell("portfolio") } else toast(authError(task.exception))
                                }
                            } catch (_: GoogleIdTokenParsingException) { toast("Could not read the Google account response.") }
                        } else toast("Choose a Google account to continue.")
                    }
                    override fun onError(e: GetCredentialException) { toast("Google sign-in could not finish: ${e.localizedMessage ?: "try again"}") }
                })
        } catch (e: Exception) { toast("Google sign-in setup is incomplete. Check Firebase setup instructions.") }
    }

    private fun showShell(page: String) {
        val root = baseRoot()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(19), dp(22), dp(28)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(7), dp(6), dp(7)); setBackgroundColor(Color.rgb(23,24,21)) }
        listOf("Portfolio", "Book", "Settings").forEach { label ->
            val key = when (label) { "Book" -> "booking"; "Settings" -> "settings"; else -> "portfolio" }
            val b = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (page == key) gold else muted); setOnClickListener { showShell(key) } }
            bar.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(bar); setContentView(root)
        when(page) { "booking" -> showBooking(); "settings" -> showSettings(); else -> showPortfolio() }
    }

    private fun showPortfolio() {
        content.removeAllViews()
        content.addView(text("CAPTURED PERSPECTIVES", 12, gold, true).apply { letterSpacing = .18f })
        content.addView(space(12)); content.addView(text("Stories,\nbeautifully held.", 36, cream, true)); content.addView(space(12))
        content.addView(text("Thoughtful photography for the moments you want to keep.", 16, muted)); content.addView(space(22))
        val hero = FrameLayout(this).apply { background = rounded(card, 22); clipToOutline = true }
        val heroImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(card) }
        hero.addView(heroImage, FrameLayout.LayoutParams(-1, dp(225))); loadImage("photo-1519741497674-611481863552", heroImage)
        val shade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xCC11120F.toInt())) }
        hero.addView(shade, FrameLayout.LayoutParams(-1, dp(225)))
        val caption = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)); gravity = Gravity.BOTTOM }
        caption.addView(text("CAPTURE THE FEELING", 11, gold, true)); caption.addView(text("Your story, in every frame", 20, Color.WHITE, true))
        hero.addView(caption, FrameLayout.LayoutParams(-1, dp(225))); content.addView(hero); content.addView(space(25))
        content.addView(text("Selected work", 23, cream, true)); content.addView(space(12))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All", "Weddings", "Portraits", "Events").forEach { label ->
            val b = TextView(this).apply { text = label; textSize = 12f; setTextColor(if (category == label) bg else muted); gravity = Gravity.CENTER; setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(if (category == label) gold else card, 24); setOnClickListener { category = label; showPortfolio() } }
            val lp = LinearLayout.LayoutParams(-2, -2); lp.marginEnd = dp(7); filters.addView(b, lp)
        }
        content.addView(filters); content.addView(space(14))
        val shown = portfolioData.filter { category == "All" || it.type.equals(category, true) }
        shown.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { index, p ->
                val tile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(card, 16); clipToOutline = true }
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(card) }
                tile.addView(image, LinearLayout.LayoutParams(-1, dp(138)))
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(9), dp(8), dp(12)) }
                details.addView(text(p.title, 14, cream, true)); details.addView(text(p.type, 11, muted)); tile.addView(details)
                val lp = LinearLayout.LayoutParams(0, -2, 1f); if (index > 0) lp.marginStart = dp(10); lp.bottomMargin = dp(10); row.addView(tile, lp)
                loadImage(p.imageId, image)
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row)
        }
        content.addView(space(12)); content.addView(text("Made for the moments that matter.", 18, cream, true)); content.addView(space(8))
        content.addView(text("Weddings · Portraits · Events", 14, muted)); content.addView(space(18)); content.addView(actionButton("Enquire about a session") { showShell("booking") })
        content.addView(space(18)); content.addView(text("Questions? hello@capturedperspectives.co.za", 13, muted).apply { setOnClickListener { sendEmail() } })
        db.collection("portfolio").get().addOnSuccessListener { snapshot ->
            if (!snapshot.isEmpty) {
                portfolioData = snapshot.documents.mapNotNull { doc ->
                    val title = doc.getString("title") ?: return@mapNotNull null
                    PortfolioItem(title, doc.getString("type") ?: "Portraits", doc.getString("imageId") ?: "photo-1519741497674-611481863552", doc.getString("description") ?: "")
                }
                if (portfolioData.isNotEmpty()) showPortfolio()
            }
        }
    }

    private fun showBooking() {
        content.removeAllViews()
        content.addView(text("CAPTURED PERSPECTIVES", 12, gold, true)); content.addView(space(16))
        content.addView(text("Let’s make it\nmemorable.", 34, cream, true)); content.addView(space(10))
        content.addView(text("Tell us what you have in mind. Your enquiry is saved securely to the booking database.", 15, muted)); content.addView(space(24))
        val name = input("Your name"); val email = input("Email address").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val service = spinner(listOf("Choose a session type", "Wedding photography", "Portrait session", "Event photography"))
        val date = input("Preferred date (optional)"); val message = input("A few details about your session", true)
        listOf(Triple("NAME", name, 54), Triple("EMAIL", email, 54), Triple("SESSION TYPE", service, 52), Triple("PREFERRED DATE", date, 54), Triple("YOUR MESSAGE", message, 125)).forEach { (label, view, height) ->
            content.addView(text(label, 11, gold, true)); content.addView(space(7)); content.addView(view, fieldParams(height)); content.addView(space(15))
        }
        content.addView(actionButton("Save booking enquiry") {
            val validation = InputValidation.booking(name.text.toString().trim(), email.text.toString().trim(), service.selectedItemPosition, message.text.toString().trim())
            if (validation != null) toast(validation)
            else {
                val user = auth.currentUser ?: return@actionButton showAuth(false)
                val booking = hashMapOf("userId" to user.uid, "name" to name.text.toString().trim(), "email" to email.text.toString().trim(), "service" to service.selectedItem.toString(), "preferredDate" to date.text.toString().trim(), "message" to message.text.toString().trim(), "status" to "new", "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
                db.collection("bookings").add(booking).addOnSuccessListener {
                    toast("Enquiry saved to Firebase.")
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hello@capturedperspectives.co.za")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "Booking enquiry – ${service.selectedItem}")
                        putExtra(Intent.EXTRA_TEXT, "Booking saved in the app.\n${booking.entries.joinToString("\n") { "${it.key}: ${it.value}" }}")
                    }
                    try { startActivity(intent) } catch (_: Exception) { }
                }.addOnFailureListener { toast("Could not save booking: ${authError(it)}") }
            }
        })
    }

    private fun showSettings() {
        content.removeAllViews(); val user = auth.currentUser ?: return showAuth(false)
        content.addView(text("YOUR ACCOUNT", 12, gold, true)); content.addView(space(14)); content.addView(text("Settings", 32, cream, true)); content.addView(space(9))
        content.addView(text("Signed in as ${user.email ?: "Google account"}", 14, muted)); content.addView(space(24))
        content.addView(text("DISPLAY NAME", 11, gold, true)); content.addView(space(7))
        val displayName = input("Your name"); displayName.setText(user.displayName ?: ""); content.addView(displayName, fieldParams(54)); content.addView(space(18))
        content.addView(text("PREFERRED SESSION", 11, gold, true)); content.addView(space(7))
        val services = listOf("No preference", "Wedding photography", "Portrait session", "Event photography")
        val preference = spinner(services)
        content.addView(preference, fieldParams(52)); content.addView(space(18))
        val updates = CheckBox(this).apply { text = "Receive occasional photography updates"; setTextColor(cream); buttonTintList = android.content.res.ColorStateList.valueOf(gold) }
        content.addView(updates); content.addView(space(18))
        db.collection("userSettings").document(user.uid).get().addOnSuccessListener { doc ->
            val saved = doc.getString("preferredSession")
            if (saved != null && services.contains(saved)) preference.setSelection(services.indexOf(saved))
            updates.isChecked = doc.getBoolean("receiveUpdates") ?: false
        }
        content.addView(actionButton("Save settings") {
            val newName = displayName.text.toString().trim()
            if (newName.length < 2) toast("Enter a display name with at least two characters.")
            else {
                val settings = hashMapOf("displayName" to newName, "email" to (user.email ?: ""), "preferredSession" to preference.selectedItem.toString(), "receiveUpdates" to updates.isChecked, "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
                db.collection("userSettings").document(user.uid).set(settings).addOnSuccessListener {
                    user.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(newName).build())
                    toast("Settings saved.")
                }.addOnFailureListener { toast("Could not save settings: ${authError(it)}") }
            }
        })
        content.addView(space(12)); content.addView(outlineButton("Sign out") { auth.signOut(); showAuth(false) })
        content.addView(space(22)); content.addView(text("Help and support", 19, cream, true)); content.addView(space(8))
        content.addView(text("Browse the portfolio by category. Choose Book to send a session enquiry. In Settings, update your name and session preference. Sign-in and booking data are stored in Firebase. Contact hello@capturedperspectives.co.za for assistance.", 14, muted))
    }

    private fun saveProfile(name: String, email: String) {
        val user = auth.currentUser ?: return
        db.collection("userSettings").document(user.uid).set(mapOf("displayName" to name, "email" to email, "preferredSession" to "No preference", "receiveUpdates" to false, "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
    }

    private fun seedDemoCollections() {
        val collections = mapOf(
            "portfolio" to samplePortfolio().mapIndexed { i, p -> "photo${(i + 1).toString().padStart(2, '0')}" to mapOf("title" to p.title, "type" to p.type, "imageId" to p.imageId, "description" to p.description) },
            "services" to listOf("Wedding photography", "Portrait session", "Event photography", "Engagement session", "Family portraits", "Graduation portraits", "Maternity session", "Brand photography", "Couple session", "Mini session").mapIndexed { i, v -> "service${(i + 1).toString().padStart(2, '0')}" to mapOf("name" to v, "active" to true, "sortOrder" to i + 1) },
            "testimonials" to listOf("A beautiful experience from beginning to end.", "Every photograph feels like us.", "The photos brought the day right back.", "Warm, patient, and wonderfully talented.", "We felt comfortable in every frame.", "The moments were captured perfectly.", "A joy to work with and the results are stunning.", "Our family will treasure these images.", "The whole process was easy and personal.", "We could not be happier with our gallery.").mapIndexed { i, v -> "review${(i + 1).toString().padStart(2, '0')}" to mapOf("text" to v, "author" to "Sample client ${i + 1}", "rating" to 5) },
            "users" to (1..10).map { i -> "sampleUser${i.toString().padStart(2, '0')}" to mapOf("displayName" to "Sample client $i", "email" to "client$i@example.invalid", "preferredSession" to listOf("Wedding photography", "Portrait session", "Event photography")[(i - 1) % 3], "isSample" to true) }
        )
        collections.forEach { (collection, records) ->
            db.collection(collection).limit(1).get().addOnSuccessListener { result ->
                if (result.isEmpty) {
                    val batch = db.batch()
                    records.forEach { (id, fields) -> batch.set(db.collection(collection).document(id), fields) }
                    batch.commit()
                }
            }
        }
        val uid = auth.currentUser?.uid ?: return
        db.collection("bookings").whereEqualTo("userId", uid).whereEqualTo("isSample", true).limit(1).get().addOnSuccessListener { existingSamples ->
            if (existingSamples.isEmpty) {
                val sampleServices = listOf("Wedding photography", "Portrait session", "Event photography")
                val batch = db.batch()
                (1..10).forEach { i ->
                    val booking = mapOf("userId" to uid, "name" to "Sample booking client $i", "email" to "booking$i@example.invalid", "service" to sampleServices[(i - 1) % 3], "preferredDate" to "2026-${((i - 1) % 12 + 1).toString().padStart(2, '0')}-15", "message" to "Simulated booking record $i", "status" to "sample", "isSample" to true)
                    batch.set(db.collection("bookings").document("demo_${uid}_booking_${i.toString().padStart(2, '0')}"), booking)
                }
                batch.commit()
            }
        }
    }

    private fun samplePortfolio() = listOf(
        PortfolioItem("Golden hour vows", "Weddings", "photo-1519741497674-611481863552"), PortfolioItem("Quiet moments", "Portraits", "photo-1534528741775-53994a69daeb"),
        PortfolioItem("The celebration", "Events", "photo-1511795409834-ef04bbd61622"), PortfolioItem("Soft light", "Portraits", "photo-1508214751196-bcfd4ca60f91"),
        PortfolioItem("A day to remember", "Weddings", "photo-1537633552985-df8429e8048b"), PortfolioItem("Together", "Events", "photo-1511895426328-dc8714191300"),
        PortfolioItem("The quiet in between", "Portraits", "photo-1524504388940-b1c1722653e1"), PortfolioItem("A family gathering", "Events", "photo-1511988617509-a57c8a288659"),
        PortfolioItem("Just the two of us", "Weddings", "photo-1511285560929-80b456fea0bc"), PortfolioItem("A little celebration", "Events", "photo-1464366400600-7168b8af9bc3")
    )

    private fun sendEmail() { try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:hello@capturedperspectives.co.za"))) } catch (_: Exception) { toast("No email app is available.") } }
    private fun authError(error: Throwable?) = error?.localizedMessage?.substringBefore(" (" ) ?: "Please try again."
    private fun baseRoot() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
    private fun fieldParams(height: Int) = LinearLayout.LayoutParams(-1, dp(height))
    private fun input(label: String, multiline: Boolean = false) = EditText(this).apply {
        hint = label; textSize = 14f; setTextColor(cream); setHintTextColor(muted); setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(card, 12)
        if (multiline) { gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
    }
    private fun spinner(options: List<String>) = Spinner(this).apply {
        val listAdapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(cream); textSize = 14f }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(cream); setBackgroundColor(card) }
        }
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter = listAdapter; setPadding(dp(12), dp(4), dp(12), dp(4)); background = rounded(card, 12)
    }
    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply { text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(bg); background = rounded(gold, 14); setPadding(dp(12), dp(16), dp(12), dp(16)); setOnClickListener { action() } }
    private fun outlineButton(label: String, action: () -> Unit) = TextView(this).apply { text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(cream); background = rounded(card, 14); setPadding(dp(12), dp(15), dp(12), dp(15)); setOnClickListener { action() } }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD); setLineSpacing(dp(3).toFloat(), 1f) }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun loadImage(id: String, target: ImageView) {
        pool.execute {
            try {
                val conn = URL("https://images.unsplash.com/$id?w=900&auto=format&fit=crop&q=80").openConnection(); conn.connectTimeout = 10000; conn.readTimeout = 15000
                val bitmap = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) runOnUiThread { target.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }
    }
}
