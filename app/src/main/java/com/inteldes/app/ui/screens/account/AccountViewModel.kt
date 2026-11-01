package com.inteldes.app.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.server.AccountService
import com.inteldes.app.data.server.LoginResult
import com.inteldes.app.data.server.PairInspect
import com.inteldes.app.data.server.PairingLink
import com.inteldes.app.data.server.Session
import com.inteldes.app.data.server.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoginUiState(val loading: Boolean = false, val error: String? = null)

/** Alur B (a new phone claims an `add_phone` QR): claim first, then the user confirms the account it landed in. */
sealed interface ClaimUiState {
    data object Working : ClaimUiState
    data class Ready(val result: LoginResult) : ClaimUiState
    data class Failed(val message: String) : ClaimUiState
    data object Done : ClaimUiState
}

/** Alur A (this phone approves a web login): look at who is asking, then approve. */
sealed interface ApproveUiState {
    data object Loading : ApproveUiState
    data class Ready(val info: PairInspect, val approving: Boolean = false, val error: String? = null) : ApproveUiState
    data class Failed(val message: String) : ApproveUiState
    data object Done : ApproveUiState
}

/** Marks a claim code that is really the one-time code of a browser login. */
const val APP_LOGIN_PREFIX = "app_"

class AccountViewModel(private val account: AccountService) : ViewModel() {
    val session: StateFlow<Session?> = account.session
    val endedByServer: StateFlow<Boolean> = account.endedByServer
    val deviceName: String get() = account.deviceName

    // ───────────────────────── login ─────────────────────────

    private val _providers = MutableStateFlow(com.inteldes.app.data.server.Providers())
    val providers: StateFlow<com.inteldes.app.data.server.Providers> = _providers

    init { viewModelScope.launch { _providers.value = account.providers() } }

    /** Opens the server's own Google / portal login in the browser via [open]; the secrets stay on the server. */
    fun startExternalLogin(provider: String, open: (String) -> Unit) {
        val url = account.beginAppLogin(provider)
        if (url == null) _login.value = LoginUiState(error = "Login lewat browser belum tersedia di perangkat ini.") else open(url)
    }

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login

    fun login(email: String, password: String, onDone: () -> Unit) {
        if (_login.value.loading) return                          // one request in flight
        _login.value = LoginUiState(loading = true)
        viewModelScope.launch {
            try {
                account.login(email, password)
                _login.value = LoginUiState()
                onDone()
            } catch (e: Exception) {
                _login.value = LoginUiState(error = userMessage(e))
            }
        }
    }

    fun skipLogin() { account.loginSkipped = true }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch { account.logout(); onDone() }
    }

    // ───────────────────────── QR ─────────────────────────

    /** Null for a QR that isn't from our server: the caller must not make any request for it. */
    fun parseQr(raw: String): PairingLink? = account.parseQr(raw)

    private val _claim = MutableStateFlow<ClaimUiState>(ClaimUiState.Working)
    val claim: StateFlow<ClaimUiState> = _claim
    private var claimedCode: String? = null

    /** Runs once per code: a recomposition or rotation must not try to claim a single-use code twice. */
    fun startClaim(code: String) {
        if (claimedCode == code) return
        claimedCode = code
        _claim.value = ClaimUiState.Working
        viewModelScope.launch {
            _claim.value = try {
                // "app_<code>" comes from a Google / portal login in the browser, a plain code from a QR.
                ClaimUiState.Ready(if (code.startsWith(APP_LOGIN_PREFIX)) account.exchangeAppLogin(code.removePrefix(APP_LOGIN_PREFIX)) else account.claim(code))
            } catch (e: Exception) {
                ClaimUiState.Failed(userMessage(e))
            }
        }
    }

    fun confirmClaim(result: LoginResult, onDone: () -> Unit) {
        viewModelScope.launch {
            account.commit(result)
            _claim.value = ClaimUiState.Done
            onDone()
        }
    }

    fun cancelClaim(result: LoginResult, onDone: () -> Unit) {
        viewModelScope.launch { account.discard(result); onDone() }
    }

    private val _approve = MutableStateFlow<ApproveUiState>(ApproveUiState.Loading)
    val approve: StateFlow<ApproveUiState> = _approve
    private var inspectedCode: String? = null

    fun loadApprove(code: String) {
        if (inspectedCode == code) return
        inspectedCode = code
        _approve.value = ApproveUiState.Loading
        viewModelScope.launch {
            _approve.value = try {
                ApproveUiState.Ready(account.inspect(code))
            } catch (e: Exception) {
                ApproveUiState.Failed(userMessage(e))
            }
        }
    }

    fun approve(code: String) {
        val ready = _approve.value as? ApproveUiState.Ready ?: return
        if (ready.approving) return                               // double tap
        _approve.value = ready.copy(approving = true, error = null)
        viewModelScope.launch {
            _approve.value = try {
                account.approve(code)
                ApproveUiState.Done
            } catch (e: Exception) {
                ready.copy(approving = false, error = userMessage(e))
            }
        }
    }
}
