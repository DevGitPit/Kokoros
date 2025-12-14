// background.js

// Ensure the offscreen document exists
async function setupOffscreenDocument() {
  const existingContexts = await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'],
    documentUrls: [chrome.runtime.getURL('offscreen.html')]
  });

  if (existingContexts.length > 0) return;

  await chrome.offscreen.createDocument({
    url: 'offscreen.html',
    reasons: ['AUDIO_PLAYBACK'],
    justification: 'Streaming TTS audio in the background',
  });
}

// Handle Keyboard Shortcuts
chrome.commands.onCommand.addListener((command) => {
  if (command === 'stop-playback') {
    chrome.runtime.sendMessage({ type: 'ACT_STOP' });
  }
});

// Listen for messages from Popup & Offscreen
chrome.runtime.onMessage.addListener(async (msg, sender, sendResponse) => {
  // Update Badge State based on playback
  if (msg.type === 'UPDATE_PROGRESS') {
    chrome.action.setBadgeText({ text: '▶' });
    chrome.action.setBadgeBackgroundColor({ color: '#4CAF50' });
  } 
  else if (msg.type === 'PLAYBACK_FINISHED') {
    chrome.action.setBadgeText({ text: '' });
  }

  // Setup offscreen if needed for the stream command
  if (msg.type === 'CMD_START_STREAM') {
    await setupOffscreenDocument();
    
    chrome.runtime.sendMessage({
      type: 'ACT_STREAM',
      payload: msg.payload
    });
  } 
  else if (msg.type === 'CMD_STOP') {
    chrome.runtime.sendMessage({ type: 'ACT_STOP' });
  }
});