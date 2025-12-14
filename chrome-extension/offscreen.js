// offscreen.js - FIXED VERSION (Streaming Only & Optimized Caching)

// --- State ---
let audioContext = null;
let streamAbortController = null;
let audioQueue = [];
let isStreaming = false;
let isPaused = false;
let nextStartTime = 0;
let lastPlayedIndex = 0;
let activeSources = [];

// Session Cache (Index -> AudioBuffer)
// Stores recently fetched sentences to save compute/battery on rewinds/pauses
let sessionCache = new Map();

// Stream Resume Cache
let lastStreamParams = null; // { text, voice, speed }

const LOCAL_SERVER_URL = 'http://localhost:3000';

// Artwork for Media Session
let artworkDataUrl = null;

// --- Helper: Load Artwork ---
async function loadArtwork() {
    if (artworkDataUrl) return artworkDataUrl;
    
    try {
        const iconPath = chrome.runtime.getURL('icons/icon128.png');
        const response = await fetch(iconPath);
        const blob = await response.blob();
        
        return new Promise((resolve) => {
            const reader = new FileReader();
            reader.onloadend = () => {
                artworkDataUrl = reader.result;
                console.log('✅ Artwork converted to data URL');
                resolve(artworkDataUrl);
            };
            reader.onerror = () => {
                console.warn('❌ Failed to read artwork');
                resolve(null);
            };
            reader.readAsDataURL(blob);
        });
    } catch (e) {
        console.warn('❌ Failed to load artwork:', e);
        return null;
    }
}

// --- CRITICAL FIX: Create silent audio to keep session alive ---
let keepAliveAudio = null;

function createKeepAliveAudio() {
    if (!audioContext) return;
    
    // Create 1 second of silence
    const sampleRate = audioContext.sampleRate;
    const buffer = audioContext.createBuffer(1, sampleRate * 1, sampleRate);
    
    keepAliveAudio = audioContext.createBufferSource();
    keepAliveAudio.buffer = buffer;
    keepAliveAudio.loop = true;
    keepAliveAudio.connect(audioContext.destination);
    keepAliveAudio.start();
}

// --- Media Session Setup ---
async function setupMediaSession() {
    if (!('mediaSession' in navigator)) {
        console.warn('❌ Media Session API not available');
        return false;
    }

    console.log('✅ Setting up Media Session...');

    // Load artwork (Data URL) to avoid scheme errors
    const artwork = await loadArtwork();

    const metadata = {
        title: 'Kokoro TTS Reader',
        artist: 'Streaming Audio',
        album: 'Text-to-Speech'
    };

    if (artwork) {
        metadata.artwork = [
            { src: artwork, sizes: '128x128', type: 'image/png' }
        ];
    }

    // Set metadata
    navigator.mediaSession.metadata = new MediaMetadata(metadata);

    // Set playback state
    navigator.mediaSession.playbackState = 'playing';

    // Action handlers
    navigator.mediaSession.setActionHandler('play', () => {
        console.log('🎧 Headphone PLAY pressed');
        if (lastStreamParams) {
            handleStreamRequest(lastStreamParams);
        }
    });

    navigator.mediaSession.setActionHandler('pause', () => {
        console.log('🎧 Headphone PAUSE pressed');
        userPause();
    });

    navigator.mediaSession.setActionHandler('stop', () => {
        console.log('🎧 Headphone STOP pressed');
        userPause();
    });

    navigator.mediaSession.setActionHandler('previoustrack', () => {
        console.log('🎧 Headphone PREVIOUS pressed');
        if (lastStreamParams && lastPlayedIndex > 0) {
            stopAllAudio();
            const text = lastStreamParams.text;
            const sentences = splitIntoSentences(text);
            const newIndex = Math.max(0, lastPlayedIndex - 1);
            startStreaming({ ...lastStreamParams, bufferTarget: 2 }, newIndex);
        }
    });

    navigator.mediaSession.setActionHandler('nexttrack', () => {
        console.log('🎧 Headphone NEXT pressed');
        if (lastStreamParams) {
            stopAllAudio();
            const text = lastStreamParams.text;
            const sentences = splitIntoSentences(text);
            if (lastPlayedIndex < sentences.length - 1) {
                startStreaming({ ...lastStreamParams, bufferTarget: 2 }, lastPlayedIndex + 1);
            }
        }
    });

    return true;
}

// --- Common Setup ---
function initAudioContext() {
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (!audioContext) {
        audioContext = new AudioCtor({ sampleRate: 24000 });
        console.log('✅ AudioContext created');
    }
    if (audioContext.state === 'suspended') {
        audioContext.resume();
    }
}

function stopAllAudio() {
    activeSources.forEach(source => {
        try { source.stop(); } catch(e) {}
    });
    activeSources = [];
}

// --- CRITICAL: Grab Audio Focus ---
async function grabAudioFocus() {
    initAudioContext();
    
    // Create silent audio to grab focus
    if (!keepAliveAudio) {
        createKeepAliveAudio();
    }
    
    // Setup Media Session (THIS IS CRITICAL!)
    const success = await setupMediaSession();
    
    if (!success) {
        console.warn('⚠️ Media Session setup failed');
        return false;
    }
    
    // Force playback state to playing
    navigator.mediaSession.playbackState = 'playing';
    
    console.log('✅ Audio focus grabbed, Media Session active');
    return true;
}

// --- STOP / PAUSE ---
async function userPause() {
    console.log('⏸️ User paused');
    stopAllAudio();
    
    if (audioContext && audioContext.state === 'running') {
        await audioContext.suspend();
    }
    
    isPaused = true;
    isStreaming = false;
    
    if (streamAbortController) streamAbortController.abort();

    // Update Media Session
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'paused';
        console.log('📱 Media Session set to PAUSED');
    }
    
    chrome.runtime.sendMessage({ type: 'PLAYBACK_FINISHED' });
}

// --- Listeners ---
chrome.runtime.onMessage.addListener((msg) => {
    console.log('📨 Offscreen received:', msg.type);
    
    switch (msg.type) {
        case 'ACT_STREAM':
            handleStreamRequest(msg.payload);
            break;
        case 'ACT_STOP':
            userPause();
            break;
    }
});

// --- Helper: Normalize Text for Comparison ---
function normalizeTextForComparison(text) {
    // Remove all whitespace to compare core content
    return text ? text.replace(/\s+/g, '') : '';
}

// --- STREAMING LOGIC ---
async function handleStreamRequest(payload) {
    console.log('🎤 Starting stream request...');
    
    // CRITICAL: Grab audio focus first!
    await grabAudioFocus();
    
    initAudioContext();
    
    // Loose comparison to ignore whitespace differences (newlines vs spaces)
    const isSameText = lastStreamParams && 
                       normalizeTextForComparison(lastStreamParams.text) === normalizeTextForComparison(payload.text);
    
    const isSameSettings = lastStreamParams && 
                           lastStreamParams.voice === payload.voice && 
                           lastStreamParams.speed === payload.speed;

    if (isSameText) {
        console.log("▶️ Resuming/Updating stream...");
        stopAllAudio();
        
        if (!isSameSettings) {
            console.log("Settings changed (voice/speed), clearing audio cache...");
            sessionCache.clear(); // Voice/Speed changed -> regenerate audio
        }
        
        if (audioContext.state === 'suspended') await audioContext.resume();
        
        // Update params so fetchers use new settings
        lastStreamParams = payload;
        
        // Resume from previous sentence context
        const resumeIndex = Math.max(0, lastPlayedIndex - 1);
        startStreaming(payload, resumeIndex);
        
    } else {
        console.log("▶️ Starting new text stream...");
        resetAudioState();
        sessionCache.clear(); // New text -> clear all cache
        lastStreamParams = payload;
        startStreaming(payload, 0);
    }
}

async function startStreaming({ text, voice, speed, bufferTarget }, startIndex) {
    isStreaming = true;
    isPaused = false;
    
    if (!audioContext) initAudioContext();
    if (audioContext.state === 'suspended') await audioContext.resume();

    audioQueue = [];
    nextStartTime = audioContext.currentTime + 0.1;

    streamAbortController = new AbortController();
    const signal = streamAbortController.signal;
    const sentences = splitIntoSentences(text);

    // Update Media Session
    if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata.artist = 'Streaming';
        navigator.mediaSession.playbackState = 'playing';
        console.log('📱 Media Session: PLAYING');
    }

    processFetchLoop(sentences, voice, speed, bufferTarget, signal, startIndex);
}

function processFetchLoop(sentences, voice, speed, bufferTarget, signal, startIndex) {
    let fetchIndex = startIndex;
    let hasStartedPlaying = false;

    const loop = async () => {
        if (signal.aborted) return;

        // Fetch ahead
        if (fetchIndex < sentences.length && audioQueue.length < 20) {
            const currentIndex = fetchIndex;
            fetchIndex++;
            
            // Check Session Cache First
            if (sessionCache.has(currentIndex)) {
                // Use cached buffer
                const buffer = sessionCache.get(currentIndex);
                audioQueue.push({ buffer, index: currentIndex });
                loop(); 
            } else {
                // Fetch from Server
                fetchSentence(sentences[currentIndex], voice, speed, signal)
                    .then(buffer => {
                        if (signal.aborted || !buffer) return;
                        
                        // Cache the buffer
                        sessionCache.set(currentIndex, buffer);
                        
                        // Prune old cache to save RAM (keep last 10 items)
                        // This allows going back a bit without refetching, but keeps RAM usage low
                        if (sessionCache.size > 10) { 
                             const firstKey = sessionCache.keys().next().value;
                             // Only delete if it's far behind current play head
                             if (firstKey < currentIndex - 5) {
                                 sessionCache.delete(firstKey);
                             }
                        }
                        
                        audioQueue.push({ buffer, index: currentIndex });
                        loop();
                    })
                    .catch(() => {});
            }
        }

        // Play trigger
        if (!hasStartedPlaying) {
            const remaining = sentences.length - startIndex;
            const effectiveTarget = Math.min(bufferTarget, remaining);
            
            if (audioQueue.length >= effectiveTarget || (fetchIndex >= sentences.length && audioQueue.length > 0)) {
                hasStartedPlaying = true;
                scheduleNext();
            }
        } else {
            scheduleNext();
        }
    };

    function scheduleNext() {
        if (signal.aborted) return;
        audioQueue.sort((a, b) => a.index - b.index);

        while (audioQueue.length > 0) {
            const item = audioQueue.shift();
            
            const source = audioContext.createBufferSource();
            source.buffer = item.buffer;
            source.connect(audioContext.destination);
            activeSources.push(source);
            
            const now = audioContext.currentTime;
            const startTime = Math.max(now, nextStartTime);
            
            source.start(startTime);
            nextStartTime = startTime + item.buffer.duration;
            
            const highlightIndex = item.index;
            const performHighlight = () => {
                if (isStreaming && !signal.aborted) {
                    lastPlayedIndex = highlightIndex;
                    chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: highlightIndex });
                    
                    // Update Media Session position
                    if ('mediaSession' in navigator) {
                        navigator.mediaSession.metadata.album = `Sentence ${highlightIndex + 1}/${sentences.length}`;
                        try {
                            navigator.mediaSession.setPositionState({
                                duration: sentences.length,
                                playbackRate: 1.0,
                                position: highlightIndex
                            });
                        } catch(e) {}
                    }
                }
            };

            source.addEventListener('ended', () => {
                const idx = activeSources.indexOf(source);
                if (idx > -1) activeSources.splice(idx, 1);
            });

            const outputLatency = (audioContext.outputLatency || 0.05) * 1000;
            const delay = Math.max(0, (startTime - now) * 1000);
            setTimeout(performHighlight, delay + outputLatency + 20);

            // End check
            if (audioQueue.length === 0 && fetchIndex >= sentences.length) {
                source.addEventListener('ended', () => {
                    setTimeout(() => {
                        if (isStreaming && !signal.aborted) {
                            console.log('✅ Stream finished naturally');
                            userPause();
                        }
                    }, 200);
                });
            }
        }
    }
    
    loop();
}

// --- Helpers ---
function resetAudioState() {
    stopAllAudio();
    isStreaming = false;
    isPaused = false;
    audioQueue = [];
    lastStreamParams = null;
    if (streamAbortController) streamAbortController.abort();
}

async function fetchSentence(text, voice, speed, signal) {
    try {
        const res = await fetch(`${LOCAL_SERVER_URL}/v1/audio/speech`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model: 'tts-1', 
                input: text, 
                voice: voice, 
                speed: speed, 
                stream: false, 
                response_format: 'mp3'
            }),
            signal: signal
        });
        if (!res.ok) throw new Error('Fetch failed');
        const ab = await res.arrayBuffer();
        return await audioContext.decodeAudioData(ab);
    } catch (e) { 
        return null; 
    } 
}

function splitIntoSentences(text) {
    if (!text) return [];
    
    // FIX: Properly escape newline characters
    const cleanText = text.replace(/[\r\n]+/g, ' ');
    
    if (!('Segmenter' in Intl)) {
        return cleanText.split(/[.!?]+/).map(s => s.trim()).filter(s => s);
    }
    
    const segmenter = new Intl.Segmenter('en', { granularity: 'sentence' });
    return Array.from(segmenter.segment(cleanText))
        .map(s => s.segment.trim())
        .filter(s => s.length > 0);
}

// --- Initialize on load ---
console.log('🎬 Offscreen document loaded');
console.log('📱 Media Session available:', 'mediaSession' in navigator);
