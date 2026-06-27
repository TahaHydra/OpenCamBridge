/**
 * Proof of Concept: OBS Virtual Camera Automation
 * 
 * This script proves that we can bypass the Windows Driver Signing requirement
 * by programmatically taking control of OBS Studio's signed Virtual Camera driver.
 * 
 * Requirements:
 * 1. OBS Studio running with WebSocket Server enabled (v5) on port 4455.
 * 2. npm install ws
 */
const WebSocket = require('ws');

// OBS WebSocket v5 defaults to port 4455
const ws = new WebSocket('ws://localhost:4455');
let messageId = 0;

ws.on('open', () => {
    console.log('[+] Connected to OBS WebSocket.');
    
    // In a real implementation, we would handle the Hello/Identify authentication handshake here
    // For this POC, we assume OBS WebSocket authentication is disabled.
    
    // Wait a brief moment for the connection to fully initialize
    setTimeout(async () => {
        try {
            console.log('[+] Checking OBS Virtual Camera status...');
            await sendRequest('GetVirtualCamStatus', {});

            console.log('[+] Creating Window Capture source for OpenCamBridge...');
            await sendRequest('CreateInput', {
                sceneName: "Scene", // Default OBS scene name
                inputName: "OpenCamBridge Virtual Feed",
                inputKind: "window_capture",
                inputSettings: {
                    // Match the Tauri application window
                    window: "tauri-app.exe:OpenCamBridge"
                }
            }).catch(e => console.log('[-] Note: Source might already exist.'));

            console.log('[+] Starting Virtual Camera...');
            await sendRequest('StartVirtualCam', {});
            
            console.log('[✓] SUCCESS: OpenCamBridge is now live on the OBS Virtual Camera!');
            ws.close();
        } catch (err) {
            console.error('[-] POC Error:', err);
            ws.close();
        }
    }, 1000);
});

ws.on('error', (err) => {
    console.error('[-] Failed to connect to OBS. Is OBS Studio running with WebSockets enabled?', err.message);
});

function sendRequest(requestType, requestData) {
    return new Promise((resolve, reject) => {
        const id = `req-${++messageId}`;
        
        const handleMessage = (data) => {
            const response = JSON.parse(data.toString());
            // OBS WebSocket v5 wraps responses in op: 7
            if (response.op === 7 && response.d && response.d.requestId === id) {
                ws.removeListener('message', handleMessage);
                if (response.d.requestStatus && response.d.requestStatus.result) {
                    resolve(response.d.responseData);
                } else {
                    reject(response.d.requestStatus);
                }
            }
        };
        
        ws.on('message', handleMessage);
        
        ws.send(JSON.stringify({
            op: 6, // Request opcode
            d: {
                requestType,
                requestId: id,
                requestData
            }
        }));
    });
}
