/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { useState, useEffect, useCallback } from 'react';

// Define the global VaaBridge interface for TypeScript
declare global {
  interface Window {
    VaaBridge?: {
      loadModel: (path: string, contextSize: number, useGpu: boolean, onLoaded: string) => void;
      sendMessage: (prompt: string, systemPrompt: string) => void;
    };
    onVaaToken?: (token: string) => void;
    onVaaComplete?: () => void;
    onModelReady?: () => void;
  }
}

export default function App() {
  const [messages, setMessages] = useState<{ role: 'user' | 'assistant'; content: string }[]>([]);
  const [input, setInput] = useState('');
  const [isModelLoaded, setIsModelLoaded] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);
  const [modelPath, setModelPath] = useState('/sdcard/Download/model.gguf');

  useEffect(() => {
    window.onModelReady = () => {
      setIsModelLoaded(true);
      setMessages(prev => [...prev, { role: 'assistant', content: 'Model loaded successfully!' }]);
    };

    window.onVaaToken = (token: string) => {
      setMessages(prev => {
        const last = prev[prev.length - 1];
        if (last && last.role === 'assistant') {
          return [...prev.slice(0, -1), { ...last, content: last.content + token }];
        } else {
          return [...prev, { role: 'assistant', content: token }];
        }
      });
    };

    window.onVaaComplete = () => {
      setIsGenerating(false);
    };

    return () => {
      delete window.onModelReady;
      delete window.onVaaToken;
      delete window.onVaaComplete;
    };
  }, []);

  const handleLoadModel = () => {
    if (window.VaaBridge) {
      window.VaaBridge.loadModel(modelPath, 2048, true, 'onModelReady');
      setMessages([{ role: 'assistant', content: `Loading model from ${modelPath}...` }]);
    } else {
      setMessages([{ role: 'assistant', content: 'Error: VaaBridge not found. Please ensure you are running this inside the SmolChat Android app.' }]);
    }
  };

  const handleSend = () => {
    if (!input.trim() || isGenerating || !isModelLoaded) return;

    const userMessage = input.trim();
    setMessages(prev => [...prev, { role: 'user', content: userMessage }]);
    setInput('');
    setIsGenerating(true);

    if (window.VaaBridge) {
      window.VaaBridge.sendMessage(userMessage, 'You are a helpful assistant.');
    }
  };

  return (
    <div className="flex flex-col h-screen bg-gray-50 text-gray-900 font-sans p-4">
      <div className="flex-none pb-4 border-b">
        <h1 className="text-xl font-bold">Vaa Bridge Chat</h1>
        {!isModelLoaded ? (
          <div className="mt-2 flex gap-2">
            <input
              type="text"
              value={modelPath}
              onChange={(e) => setModelPath(e.target.value)}
              className="flex-1 p-2 border rounded"
              placeholder="Model path (e.g. /sdcard/model.gguf)"
            />
            <button
              onClick={handleLoadModel}
              className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
            >
              Load Model
            </button>
          </div>
        ) : (
          <div className="mt-2 text-sm text-green-600 font-medium">Model Active</div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto space-y-4 py-4">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-[80%] p-3 rounded-lg ${
                msg.role === 'user'
                  ? 'bg-blue-600 text-white'
                  : 'bg-white border text-gray-800'
              }`}
            >
              <div className="whitespace-pre-wrap">{msg.content}</div>
            </div>
          </div>
        ))}
        {isGenerating && (
          <div className="flex justify-start">
            <div className="bg-white border p-3 rounded-lg animate-pulse text-gray-400">
              Generating...
            </div>
          </div>
        )}
      </div>

      <div className="flex-none pt-4 border-t flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyPress={(e) => e.key === 'Enter' && handleSend()}
          className="flex-1 p-3 border rounded-lg focus:ring-2 focus:ring-blue-500 outline-none"
          placeholder={isModelLoaded ? "Type a message..." : "Load model first..."}
          disabled={!isModelLoaded || isGenerating}
        />
        <button
          onClick={handleSend}
          disabled={!isModelLoaded || isGenerating || !input.trim()}
          className="px-6 py-3 bg-blue-600 text-white rounded-lg disabled:bg-gray-400 disabled:cursor-not-allowed hover:bg-blue-700"
        >
          Send
        </button>
      </div>
    </div>
  );
}

