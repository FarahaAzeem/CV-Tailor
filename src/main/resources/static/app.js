/**
 * CV Tailor AI - Frontend JavaScript Application
 * Manages JWT Auth, API calls to Spring Boot endpoints, UI state, and AI tailoring workflow.
 */

// Global State
let currentUser = null;
let currentToken = null;
let activeCv = null;
let savedCvs = [];

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    checkExistingSession();
});

/* ==========================================================================
   AUTHENTICATION & SESSION MANAGEMENT
   ========================================================================== */

function checkExistingSession() {
    const token = sessionStorage.getItem('jwtToken');
    const userStr = sessionStorage.getItem('user');

    if (token && userStr) {
        try {
            currentToken = token;
            currentUser = JSON.parse(userStr);
            showDashboard();
        } catch (e) {
            clearSession();
            showAuthSection();
        }
    } else {
        showAuthSection();
    }
}

function saveSession(authResponse) {
    currentToken = authResponse.token;
    currentUser = {
        id: authResponse.id,
        name: authResponse.name,
        email: authResponse.email
    };

    sessionStorage.setItem('jwtToken', currentToken);
    sessionStorage.setItem('user', JSON.stringify(currentUser));
}

function clearSession() {
    currentToken = null;
    currentUser = null;
    activeCv = null;
    savedCvs = [];
    sessionStorage.removeItem('jwtToken');
    sessionStorage.removeItem('user');
}

function switchAuthTab(tab) {
    const loginForm = document.getElementById('login-form');
    const signupForm = document.getElementById('signup-form');
    const loginTabBtn = document.getElementById('tab-login-btn');
    const signupTabBtn = document.getElementById('tab-signup-btn');
    const errorMsg = document.getElementById('auth-error-msg');

    hideAuthError();

    if (tab === 'login') {
        loginForm.classList.remove('hidden');
        signupForm.classList.add('hidden');
        loginTabBtn.classList.add('active');
        signupTabBtn.classList.remove('active');
    } else {
        signupForm.classList.remove('hidden');
        loginForm.classList.add('hidden');
        signupTabBtn.classList.add('active');
        loginTabBtn.classList.remove('active');
    }
}

async function handleLogin(event) {
    event.preventDefault();
    hideAuthError();

    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;
    const spinner = document.getElementById('login-spinner');
    const btnText = document.querySelector('#login-submit-btn .btn-text');

    setLoading(spinner, btnText, true, 'Signing in...');

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        if (!response.ok) {
            const errText = await response.text();
            throw new Error(errText || 'Invalid email or password');
        }

        const data = await response.json();
        saveSession(data);
        showToast(`Welcome back, ${data.name || 'User'}!`, 'success');
        showDashboard();
    } catch (err) {
        showAuthError(err.message || 'Login failed. Please try again.');
    } finally {
        setLoading(spinner, btnText, false, 'Sign In');
    }
}

async function handleSignup(event) {
    event.preventDefault();
    hideAuthError();

    const name = document.getElementById('signup-name').value.trim();
    const email = document.getElementById('signup-email').value.trim();
    const password = document.getElementById('signup-password').value;
    const spinner = document.getElementById('signup-spinner');
    const btnText = document.querySelector('#signup-submit-btn .btn-text');

    setLoading(spinner, btnText, true, 'Creating Account...');

    try {
        const response = await fetch('/api/auth/signup', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, password })
        });

        if (!response.ok) {
            const errText = await response.text();
            throw new Error(errText || 'Registration failed. Email may already be in use.');
        }

        const data = await response.json();
        saveSession(data);
        showToast('Account created successfully!', 'success');
        showDashboard();
    } catch (err) {
        showAuthError(err.message || 'Signup failed. Please check inputs.');
    } finally {
        setLoading(spinner, btnText, false, 'Create Account');
    }
}

function handleLogout() {
    clearSession();
    showToast('Signed out successfully', 'info');
    showAuthSection();
}

function showAuthSection() {
    document.getElementById('auth-section').classList.remove('hidden');
    document.getElementById('dashboard-section').classList.add('hidden');
}

function showDashboard() {
    document.getElementById('auth-section').classList.add('hidden');
    document.getElementById('dashboard-section').classList.remove('hidden');

    // Display user profile info
    if (currentUser) {
        document.getElementById('display-user-name').textContent = currentUser.name || 'User';
        document.getElementById('display-user-email').textContent = currentUser.email || '';
        
        const initials = (currentUser.name || 'U')
            .split(' ')
            .map(n => n[0])
            .join('')
            .toUpperCase()
            .substring(0, 2);
        document.getElementById('user-avatar-initials').textContent = initials;
    }

    loadSavedCvs();
}

function showAuthError(msg) {
    const errorEl = document.getElementById('auth-error-msg');
    errorEl.textContent = msg;
    errorEl.classList.remove('hidden');
}

function hideAuthError() {
    const errorEl = document.getElementById('auth-error-msg');
    errorEl.classList.add('hidden');
}

/* ==========================================================================
   AUTHENTICATED API HELPER
   ========================================================================== */

async function apiFetch(endpoint, options = {}) {
    if (!currentToken) {
        handleLogout();
        throw new Error('Not authenticated');
    }

    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${currentToken}`,
        ...(options.headers || {})
    };

    const config = {
        ...options,
        headers
    };

    const response = await fetch(endpoint, config);

    if (response.status === 401 || response.status === 403) {
        showToast('Session expired. Please sign in again.', 'error');
        handleLogout();
        throw new Error('Session expired');
    }

    return response;
}

/* ==========================================================================
   CV CREATION & TAILORING WORKFLOW
   ========================================================================== */

async function handleTailorSubmit(event) {
    event.preventDefault();

    const title = document.getElementById('cv-title').value.trim();
    const targetJobTitle = document.getElementById('target-job-title').value.trim();
    const rawContent = document.getElementById('raw-content').value.trim();
    const jobDescription = document.getElementById('job-description').value.trim();

    if (!title || !rawContent || !jobDescription) {
        showToast('Please complete all required fields.', 'error');
        return;
    }

    // UI state updates: Show loading progress view
    showLoadingProgress('Step 1/2: Creating CV record in database...');
    updateProgress(25);

    try {
        // Step 1: POST /api/cv
        const createRes = await apiFetch('/api/cv', {
            method: 'POST',
            body: JSON.stringify({
                title: title,
                rawContent: rawContent,
                targetJobTitle: targetJobTitle || title
            })
        });

        if (!createRes.ok) {
            const err = await createRes.text();
            throw new Error(err || 'Failed to create CV entry');
        }

        const createdCv = await createRes.json();
        const cvId = createdCv.id;

        updateProgress(50);
        showLoadingProgress('Step 2/2: Tailoring resume with Gemini AI (this may take a few seconds)...');

        // Step 2: POST /api/cv/{id}/tailor
        const tailorRes = await apiFetch(`/api/cv/${cvId}/tailor`, {
            method: 'POST',
            body: JSON.stringify({
                jobDescription: jobDescription
            })
        });

        if (!tailorRes.ok) {
            const err = await tailorRes.text();
            throw new Error(err || 'Failed to tailor CV with AI');
        }

        updateProgress(90);
        const tailoredCv = await tailorRes.json();

        updateProgress(100);
        showToast('CV tailored successfully with Gemini AI!', 'success');

        // Display results
        activeCv = tailoredCv;
        renderResults(tailoredCv);

        // Reload saved CVs list
        await loadSavedCvs();

    } catch (err) {
        showToast(err.message || 'Error occurred during tailoring.', 'error');
        hideLoadingProgress();
    }
}

function showLoadingProgress(statusMessage) {
    document.getElementById('welcome-placeholder').classList.add('hidden');
    document.getElementById('results-card').classList.add('hidden');
    
    const loadingCard = document.getElementById('loading-card');
    loadingCard.classList.remove('hidden');
    
    document.getElementById('loading-status-text').textContent = statusMessage;
    
    const tailorBtn = document.getElementById('tailor-submit-btn');
    tailorBtn.disabled = true;
}

function updateProgress(percent) {
    const progressBar = document.getElementById('tailor-progress');
    if (progressBar) {
        progressBar.style.width = `${percent}%`;
    }
}

function hideLoadingProgress() {
    document.getElementById('loading-card').classList.add('hidden');
    const tailorBtn = document.getElementById('tailor-submit-btn');
    tailorBtn.disabled = false;
}

/* ==========================================================================
   SAVED CVS & HISTORY
   ========================================================================== */

async function loadSavedCvs() {
    const container = document.getElementById('cv-list-container');
    
    try {
        const response = await apiFetch('/api/cv');
        if (!response.ok) return;

        savedCvs = await response.json();

        if (!savedCvs || savedCvs.length === 0) {
            container.innerHTML = `<div class="empty-state">No saved CVs found. Create your first one above!</div>`;
            return;
        }

        container.innerHTML = savedCvs.map(cv => {
            const date = cv.createdAt ? new Date(cv.createdAt).toLocaleDateString() : '';
            const isTailored = cv.tailoredContent && cv.tailoredContent.trim().length > 0;
            const isActive = activeCv && activeCv.id === cv.id;

            return `
                <div class="cv-item ${isActive ? 'active' : ''}" onclick="selectCv(${cv.id})">
                    <div>
                        <div class="cv-item-title">${escapeHtml(cv.title)}</div>
                        <div class="cv-item-sub">
                            ${cv.targetJobTitle ? escapeHtml(cv.targetJobTitle) : 'No target title'} ${date ? `• ${date}` : ''}
                        </div>
                    </div>
                    <div style="display:flex; align-items:center; gap:8px;">
                        ${isTailored ? '<span class="badge badge-success" style="margin:0; font-size:0.65rem;">Tailored</span>' : ''}
                        <button class="btn btn-icon btn-sm" onclick="event.stopPropagation(); deleteCv(${cv.id})" title="Delete CV">
                            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <polyline points="3 6 5 6 21 6"></polyline>
                                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                            </svg>
                        </button>
                    </div>
                </div>
            `;
        }).join('');

    } catch (err) {
        container.innerHTML = `<div class="empty-state">Failed to load saved CVs.</div>`;
    }
}

async function selectCv(id) {
    try {
        const response = await apiFetch(`/api/cv/${id}`);
        if (!response.ok) throw new Error('Could not fetch CV details');

        const cv = await response.json();
        activeCv = cv;
        renderResults(cv);
        loadSavedCvs();
    } catch (err) {
        showToast(err.message, 'error');
    }
}

async function deleteCv(id) {
    if (!confirm('Are you sure you want to delete this CV?')) return;

    try {
        const response = await apiFetch(`/api/cv/${id}`, { method: 'DELETE' });
        if (response.ok) {
            showToast('CV deleted successfully', 'info');
            if (activeCv && activeCv.id === id) {
                activeCv = null;
                document.getElementById('results-card').classList.add('hidden');
                document.getElementById('welcome-placeholder').classList.remove('hidden');
            }
            loadSavedCvs();
        } else {
            throw new Error('Failed to delete CV');
        }
    } catch (err) {
        showToast(err.message, 'error');
    }
}

/* ==========================================================================
   RESULTS DISPLAY & TABS
   ========================================================================== */

function renderResults(cv) {
    hideLoadingProgress();

    document.getElementById('welcome-placeholder').classList.add('hidden');
    const resultsCard = document.getElementById('results-card');
    resultsCard.classList.remove('hidden');

    document.getElementById('result-title').textContent = cv.title || 'Untitled CV';
    document.getElementById('result-meta').textContent = cv.targetJobTitle ? `Target Position: ${cv.targetJobTitle}` : 'General Tailored CV';

    document.getElementById('tailored-content-display').textContent = cv.tailoredContent || 'No tailored content generated yet. Click "Tailor My CV" above.';
    document.getElementById('raw-content-display').textContent = cv.rawContent || 'No raw content provided.';

    switchResultTab('tailored');
}

function switchResultTab(tab) {
    const viewTailored = document.getElementById('view-tailored');
    const viewRaw = document.getElementById('view-raw');
    const tabTailoredBtn = document.getElementById('tab-tailored-btn');
    const tabRawBtn = document.getElementById('tab-raw-btn');

    if (tab === 'tailored') {
        viewTailored.classList.remove('hidden');
        viewRaw.classList.add('hidden');
        tabTailoredBtn.classList.add('active');
        tabRawBtn.classList.remove('active');
    } else {
        viewRaw.classList.remove('hidden');
        viewTailored.classList.add('hidden');
        tabRawBtn.classList.add('active');
        tabTailoredBtn.classList.remove('active');
    }
}

function copyTailoredContent() {
    if (!activeCv || !activeCv.tailoredContent) {
        showToast('No tailored content to copy', 'error');
        return;
    }

    navigator.clipboard.writeText(activeCv.tailoredContent).then(() => {
        showToast('Tailored CV copied to clipboard!', 'success');
    }).catch(() => {
        showToast('Failed to copy to clipboard', 'error');
    });
}

function downloadTailoredCv() {
    if (!activeCv || !activeCv.tailoredContent) {
        showToast('No content available to download', 'error');
        return;
    }

    const filename = `${(activeCv.title || 'Tailored_CV').replace(/[^a-z0-9]/gi, '_').toLowerCase()}_tailored.txt`;
    const blob = new Blob([activeCv.tailoredContent], { type: 'text/plain;charset=utf-8' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);
    showToast('Download started!', 'info');
}

/* ==========================================================================
   UI UTILITY FUNCTIONS
   ========================================================================== */

function setLoading(spinnerEl, textEl, isLoading, loadingText = '') {
    if (isLoading) {
        if (spinnerEl) spinnerEl.classList.remove('hidden');
        if (textEl) {
            textEl.dataset.originalText = textEl.textContent;
            textEl.textContent = loadingText;
        }
    } else {
        if (spinnerEl) spinnerEl.classList.add('hidden');
        if (textEl && textEl.dataset.originalText) {
            textEl.textContent = textEl.dataset.originalText;
        }
    }
}

function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;

    let iconSvg = '';
    if (type === 'success') {
        iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
    } else if (type === 'error') {
        iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fca5a5" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>`;
    } else {
        iconSvg = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#818cf8" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`;
    }

    toast.innerHTML = `${iconSvg} <span>${escapeHtml(message)}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        toast.style.transition = 'all 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

/* ==========================================================================
   FILE UPLOAD & DOCUMENT EXPORT
   ========================================================================== */

function triggerFileInput() {
    const fileInput = document.getElementById('cv-file-input');
    if (fileInput) fileInput.click();
}

async function handleFileSelected(event) {
    const file = event.target.files ? event.target.files[0] : null;
    if (!file) return;

    await uploadAndExtractFile(file);
}

async function uploadAndExtractFile(file) {
    const uploadStatusText = document.getElementById('upload-status-text');
    const rawTextArea = document.getElementById('raw-content');
    const titleInput = document.getElementById('cv-title');

    if (uploadStatusText) uploadStatusText.textContent = `Extracting text from ${file.name}...`;

    const formData = new FormData();
    formData.append('file', file);

    try {
        const response = await fetch('/api/cv/upload', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${currentToken}`
            },
            body: formData
        });

        if (!response.ok) {
            const errText = await response.text();
            throw new Error(errText || 'Failed to upload and parse file');
        }

        const data = await response.json();
        
        if (rawTextArea) {
            rawTextArea.value = data.extractedText || '';
        }

        if (titleInput && !titleInput.value.trim() && file.name) {
            const cleanTitle = file.name.replace(/\.[^/.]+$/, "").replace(/[-_]/g, " ");
            titleInput.value = cleanTitle;
        }

        if (uploadStatusText) uploadStatusText.textContent = `Extracted: ${file.name}`;
        showToast(`Text extracted successfully from ${file.name}`, 'success');

    } catch (err) {
        if (uploadStatusText) uploadStatusText.textContent = `Failed: ${file.name}`;
        showToast(err.message || 'Error extracting file text', 'error');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const dropzone = document.getElementById('file-dropzone');
    if (!dropzone) return;

    ['dragenter', 'dragover'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.classList.add('dragover');
        }, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.classList.remove('dragover');
        }, false);
    });

    dropzone.addEventListener('drop', (e) => {
        const dt = e.dataTransfer;
        const files = dt.files;
        if (files && files.length > 0) {
            uploadAndExtractFile(files[0]);
        }
    });
});

async function downloadDocumentFormat(format) {
    if (!activeCv || !activeCv.id) {
        showToast('No active tailored CV loaded to download', 'error');
        return;
    }

    showToast(`Generating ${format.toUpperCase()} document...`, 'info');

    try {
        const response = await apiFetch(`/api/cv/${activeCv.id}/download?format=${format}`);
        if (!response.ok) {
            throw new Error(`Failed to generate ${format.toUpperCase()} document`);
        }

        const blob = await response.blob();
        const extension = format.toLowerCase() === 'docx' ? 'docx' : 'pdf';
        const safeTitle = (activeCv.title || 'tailored_cv').replace(/[^a-z0-9]/gi, '_').toLowerCase();
        const filename = `${safeTitle}.${extension}`;

        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(link.href);

        showToast(`${format.toUpperCase()} download started!`, 'success');

    } catch (err) {
        showToast(err.message || `Download failed for ${format}`, 'error');
    }
}

