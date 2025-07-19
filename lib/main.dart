import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart'; // Corrected import for permission_handler
import 'package:notification_listener_service/notification_listener_service.dart';
import 'package:speech_to_text/speech_to_text.dart'; // Import for STT
import 'package:flutter_tts/flutter_tts.dart'; // Import for TTS
import 'package:http/http.dart' as http; // Import for HTTP requests
import 'dart:convert'; // Import for JSON encoding/decoding
import 'dart:async'; // Required for TimeoutException

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // MethodChannel for Call Detection
  static const MethodChannel _callMethodChannel = MethodChannel('com.example.secretary/call_state');
  String _callStatus = "Waiting for phone permission...";

  // MethodChannel for WhatsApp Message Detection
  static const MethodChannel _whatsappMethodChannel = MethodChannel('com.example.secretary/whatsapp_messages');
  String _whatsappStatus = "Waiting for notification access...";
  String _lastWhatsAppMessage = "No WhatsApp messages yet.";
  String _lastWhatsAppAIResponse = "AI says (WhatsApp): ..."; // Added for WhatsApp AI response display

  // MethodChannel for Accessibility Service communication
  static const MethodChannel _accessibilityMethodChannel = MethodChannel('com.example.secretary/accessibility_service');
  String _whatsappReplyStatus = "Waiting for reply..."; // Status of WhatsApp auto-reply

  // STT and TTS instances
  final SpeechToText _speechToText = SpeechToText();
  final FlutterTts _flutterTts = FlutterTts();
  bool _speechEnabled = false;
  String _lastWords = '';
  String _aiResponse = 'AI says: ...'; // For Call AI response

  // FastAPI Backend URL (IMPORTANT: Replace with your deployed Render service URL)
  final String _backendBaseUrl = 'https://secretary-ai-backend.onrender.com'; // Updated to your Render URL
  
  @override
  void initState() {
    super.initState();
    _initSpeech(); // Initialize STT
    _initTts();    // Initialize TTS

    _requestPhonePermission();
    _setupCallMethodChannel();
    _checkAndRequestNotificationAccess();
    _setupWhatsAppMethodChannel();
    _setupAccessibilityMethodChannel(); // Setup listener for accessibility service status
  }

  // --- STT Initialization ---
  void _initSpeech() async {
    _speechEnabled = await _speechToText.initialize(
      onStatus: (status) => print('STT Status: $status'),
      onError: (errorNotification) => print('STT Error: ${errorNotification.errorMsg}'),
    );
    setState(() {});
    print('STT Initialized: $_speechEnabled');
  }

  // --- TTS Initialization ---
  void _initTts() async {
    await _flutterTts.setLanguage("en-US"); // Set desired language
    await _flutterTts.setSpeechRate(0.5); // Adjust speech rate
    await _flutterTts.setVolume(1.0); // Set volume
    await _flutterTts.setPitch(1.0); // Set pitch
    print('TTS Initialized');
  }

  // --- STT Methods ---
  void _startListening() async {
    if (_speechEnabled) {
      _lastWords = ''; // Clear previous words
      await _speechToText.listen(onResult: (result) {
        setState(() {
          _lastWords = result.recognizedWords;
        });
        print('Recognized: $_lastWords');
      });
      setState(() {
        _callStatus = "Listening for your voice...";
      });
      print('STT Listening started...');
    } else {
      print('Speech recognition not available or not initialized.');
    }
  }

  void _stopListening() async {
    await _speechToText.stop();
    setState(() {
      _callStatus = 'Stopped listening.'; // Changed from _sttStatus
    });
    print('STT Listening stopped.');
    // Once stopped, send the transcribed text to the AI backend
    if (_lastWords.isNotEmpty) {
      _sendCallTextToAI("SimulatedCaller", _lastWords); // Use a placeholder caller number for now
    }
  }

  // --- TTS Method ---
  Future<void> _speak(String text) async {
    if (text.isNotEmpty) {
      setState(() {
        _aiResponse = 'AI is speaking: "$text"';
      });
      await _flutterTts.speak(text);
      print('TTS Speaking: "$text"');
    }
  }

  // --- API Call to FastAPI Backend for Calls ---
  Future<void> _sendCallTextToAI(String callerNumber, String transcribedText) async {
    setState(() {
      _aiResponse = 'AI is thinking...';
    });
    print('DEBUG CALL: Attempting to send call text to AI backend...');
    final url = Uri.parse('$_backendBaseUrl/process_call_audio');
    print('DEBUG CALL: Target URL: $url');

    try {
      print('DEBUG CALL: Making HTTP POST request for call...');
      final response = await http.post(
        url,
        headers: {'Content-Type': 'application/json'},
        body: json.encode({
          'caller_number': callerNumber,
          'transcribed_text': transcribedText,
        }),
      ).timeout(const Duration(seconds: 60)); // Increased timeout to 60 seconds

      print('DEBUG CALL: HTTP POST request completed. Status Code: ${response.statusCode}');

      if (response.statusCode == 200) {
        final Map<String, dynamic> data = json.decode(response.body);
        final String aiResponseText = data['ai_response'];
        setState(() {
          _aiResponse = 'AI says: "$aiResponseText"';
        });
        print('AI Response for Call: $aiResponseText');
        _speak(aiResponseText); // Speak the AI's response
      } else {
        setState(() {
          _aiResponse = 'AI Error (Call): ${response.statusCode} - ${response.body}';
        });
        print('Failed to get AI response for call: ${response.statusCode} - ${response.body}');
      }
    } on TimeoutException catch (e) {
      setState(() {
        _aiResponse = 'AI Connection Timeout (Call): $e';
      });
      print('Error: HTTP request for call timed out: $e');
    } catch (e) {
      setState(() {
        _aiResponse = 'Generic Error connecting to AI backend for call: $e';
      });
      print('Generic Error connecting to AI backend for call: $e');
    }
  }

  // --- API Call to FastAPI Backend for WhatsApp ---
  Future<void> _sendWhatsAppMessageToAI(String sender, String messageContent) async {
    setState(() {
      _lastWhatsAppAIResponse = 'AI is processing WhatsApp message...';
    });
    print('DEBUG WHATSAPP: Attempting to send WhatsApp message to AI backend...');
    final url = Uri.parse('$_backendBaseUrl/process_whatsapp_message');
    print('DEBUG WHATSAPP: Target URL: $url');

    try {
      print('DEBUG WHATSAPP: Making HTTP POST request for WhatsApp...');
      
      // Revised prompt for more natural, helpful responses
      final prompt = """
You are an AI assistant for WhatsApp. Your task is to provide a single, concise, and helpful reply to the user's message.
The reply should directly address the user's message and be appropriate for a WhatsApp chat.
Do not include any extra conversational filler (like "Hello to you too"), explanations, or formatting (e.g., markdown).
Just provide the exact text for the WhatsApp message.

User message: "$messageContent"
Reply:""";

      final response = await http.post(
        url,
        headers: {'Content-Type': 'application/json'},
        body: json.encode({
          'sender': sender,
          'message_content': messageContent,
          'prompt': prompt, // Pass the strict prompt to the backend
        }),
      ).timeout(const Duration(seconds: 60)); // Increased timeout to 60 seconds

      print('DEBUG WHATSAPP: HTTP POST request completed. Status Code: ${response.statusCode}');

      if (response.statusCode == 200) {
        final Map<String, dynamic> data = json.decode(response.body);
        final String aiResponseText = data['ai_response'];
        setState(() {
          _lastWhatsAppAIResponse = 'AI says (WhatsApp): "$aiResponseText"';
        });
        print('AI Response for WhatsApp: $aiResponseText');
        // Trigger auto-reply here
        _triggerWhatsAppReply(sender, aiResponseText); // Pass both sender and AI response
      } else {
        setState(() {
          _lastWhatsAppAIResponse = 'AI Error (WhatsApp): ${response.statusCode} - ${response.body}';
        });
        print('Failed to get AI response for WhatsApp: ${response.statusCode} - ${response.body}');
      }
    } on TimeoutException catch (e) {
      setState(() {
        _lastWhatsAppAIResponse = 'AI Connection Timeout (WhatsApp): $e';
      });
      print('Error: HTTP request for WhatsApp timed out: $e');
    } catch (e) {
      setState(() {
        _lastWhatsAppAIResponse = 'Generic Error connecting to AI backend for WhatsApp: $e';
      });
      print('Generic Error connecting to AI backend for WhatsApp: $e');
    }
  }

  // --- Call Detection Logic (Modified to integrate STT/TTS/AI) ---
  Future<void> _requestPhonePermission() async {
    final status = await Permission.phone.request();
    if (status.isGranted) {
      setState(() {
        _callStatus = "Phone permission granted. Waiting for calls...";
      });
      print("READ_PHONE_STATE permission granted.");
    } else if (status.isDenied) {
      setState(() {
        _callStatus = "Phone permission denied. Call detection will not work.";
      });
      print("READ_PHONE_STATE permission denied.");
    } else if (status.isPermanentlyDenied) {
      setState(() {
        _callStatus = "Phone permission permanently denied. Please enable from settings.";
      });
      print("READ_PHONE_STATE permission permanently denied. Opening app settings.");
      // Corrected call to openAppSettings
      openAppSettings(); 
    }
  }

  void _setupCallMethodChannel() {
    _callMethodChannel.setMethodCallHandler((call) async {
      if (call.method == "incomingCall") {
        final String? incomingNumber = call.arguments;
        if (incomingNumber != null) {
          setState(() {
            _callStatus = "Incoming call from: $incomingNumber";
          });
          print("Flutter received incoming call from: $incomingNumber");
          // *** AI Call Response Logic triggered here ***
          // When a call comes in, speak a greeting and then start listening
          _speak("Hello, this is your AI assistant. How can I help you?");
          // Give TTS a moment to start speaking before STT starts listening
          Future.delayed(const Duration(seconds: 3), () { // Added const
            _startListening(); // Start listening after greeting
          });
        }
      } else {
        throw PlatformException(
          code: 'UNAVAILABLE',
          message: 'Unsupported method: ${call.method}',
        );
      }
    });
    print("MethodChannel listener for 'com.example.secretary/call_state' set up.");
  }

  // --- WhatsApp Message Detection Logic (Modified to integrate AI) ---
  Future<void> _checkAndRequestNotificationAccess() async {
    bool hasAccess = await NotificationListenerService.isPermissionGranted();
    if (hasAccess) {
      setState(() {
        _whatsappStatus = "Notification access granted.";
      });
      print("Notification access granted.");
    } else {
      setState(() {
        _whatsappStatus = "Notification access denied. Please grant it.";
      });
      print("Notification access denied. Prompting user.");
      await NotificationListenerService.requestPermission();
      hasAccess = await NotificationListenerService.isPermissionGranted();
      if (hasAccess) {
        setState(() {
          _whatsappStatus = "Notification access granted.";
        });
      }
    }
  }

  void _setupWhatsAppMethodChannel() {
    _whatsappMethodChannel.setMethodCallHandler((call) async {
      if (call.method == "incomingWhatsAppMessage") {
        final Map<dynamic, dynamic>? messageData = call.arguments;
        if (messageData != null && messageData.containsKey("sender") && messageData.containsKey("message")) {
          final String sender = messageData["sender"] as String;
          // Direct cast as Kotlin should now be cleaning the message content
          final String messageContent = messageData["message"] as String; 

          setState(() {
            _lastWhatsAppMessage = "From: $sender\nMessage: $messageContent";
            _whatsappStatus = "New WhatsApp message detected!";
          });
          
          print("Flutter received WhatsApp message: From=$sender, Message='$messageContent'"); // Cleaned log

          _sendWhatsAppMessageToAI(sender, messageContent);
        }
      } else {
        throw PlatformException(
          code: 'UNAVAILABLE',
          message: 'Unsupported method: ${call.method}',
        );
      }
    });
    print("MethodChannel listener for 'com.example.secretary/whatsapp_messages' set up.");
  }

  // --- Accessibility Service Method Channel for WhatsApp Reply ---
  void _setupAccessibilityMethodChannel() {
    _accessibilityMethodChannel.setMethodCallHandler((call) async {
      if (call.method == "whatsappReplyStatus") {
        final String? status = call.arguments;
        if (status != null) {
          setState(() {
            _whatsappReplyStatus = "WhatsApp Reply Status: $status";
          });
          print("Flutter received WhatsApp Reply Status: $status");
        }
      } else {
        throw PlatformException(
          code: 'UNAVAILABLE',
          message: 'Unsupported method: ${call.method}',
        );
      }
    });
    print("MethodChannel listener for 'com.example.secretary/accessibility_service' set up.");
  }

  // --- Trigger WhatsApp Reply from Flutter ---
  Future<void> _triggerWhatsAppReply(String sender, String replyMessage) async {
    setState(() {
      _whatsappReplyStatus = "Attempting to send WhatsApp reply...";
    });
    try {
      await _accessibilityMethodChannel.invokeMethod('sendWhatsAppReply', {'sender': sender, 'message': replyMessage});
      print("Invoked native method to send WhatsApp reply: $replyMessage");
    } on PlatformException catch (e) {
      setState(() {
        _whatsappReplyStatus = "Failed to send WhatsApp reply: ${e.message}";
      });
      print("Failed to invoke native method to send WhatsApp reply: ${e.message}");
    }
  }


  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('AI Assistant'),
          backgroundColor: Colors.blueAccent,
          centerTitle: true,
        ),
        body: SingleChildScrollView(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // --- Call Detection Section ---
                  const Icon(Icons.phone_android, size: 80, color: Colors.blueAccent),
                  const SizedBox(height: 20),
                  const Text(
                    'Call Detection Status:',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 15),
                  Text(
                    _callStatus,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, color: Colors.deepPurple),
                  ),
                  const SizedBox(height: 20),
                  ElevatedButton.icon(
                    onPressed: () {
                      _requestPhonePermission();
                    },
                    icon: const Icon(Icons.security),
                    label: const Text('Check/Request Phone Permission'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                      textStyle: const TextStyle(fontSize: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                      elevation: 5,
                    ),
                  ),
                  const SizedBox(height: 40), // Spacer between sections

                  // --- STT/TTS/AI Interaction Section (for Call) ---
                  const Icon(Icons.mic, size: 80, color: Colors.redAccent),
                  const SizedBox(height: 20),
                  const Text(
                    'AI Call Interaction:',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 15),
                  Text(
                    _lastWords.isEmpty ? 'Speak after "Hello" on incoming call...' : 'You said: "$_lastWords"',
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, color: Colors.brown),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    _aiResponse, // This is for call-related AI responses
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, color: Colors.blueGrey, fontStyle: FontStyle.italic),
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      ElevatedButton.icon(
                        onPressed: _speechToText.isListening ? null : _startListening,
                        icon: Icon(_speechToText.isListening ? Icons.mic_off : Icons.mic),
                        label: Text(_speechToText.isListening ? 'Listening...' : 'Start Listening'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _speechToText.isListening ? Colors.red : Colors.green,
                          padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 10),
                          textStyle: const TextStyle(fontSize: 15),
                        ),
                      ),
                      const SizedBox(width: 10),
                      ElevatedButton.icon(
                        onPressed: _speechToText.isNotListening ? null : _stopListening,
                        icon: const Icon(Icons.stop),
                        label: const Text('Stop Listening'),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.orange,
                          padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 10),
                          textStyle: const TextStyle(fontSize: 15),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 40), // Spacer between sections


                  // --- WhatsApp Detection Section ---
                  const Icon(Icons.message, size: 80, color: Colors.green),
                  const SizedBox(height: 20),
                  const Text(
                    'WhatsApp Message Detection:',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 15),
                  Text(
                    _whatsappStatus,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, color: Colors.teal),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    _lastWhatsAppMessage,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 16, fontStyle: FontStyle.italic),
                  ),
                  const SizedBox(height: 10), // Added spacing
                  Text(
                    _lastWhatsAppAIResponse, // Display AI response for WhatsApp here
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18, color: Colors.purple, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 10), // Added spacing for reply status
                  Text(
                    _whatsappReplyStatus, // Display WhatsApp auto-reply status
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 16, color: Colors.blueGrey),
                  ),
                  const SizedBox(height: 20),
                  ElevatedButton.icon(
                    onPressed: () {
                      _checkAndRequestNotificationAccess();
                    },
                    icon: const Icon(Icons.notifications_active),
                    label: const Text('Grant Notification Access'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                      textStyle: const TextStyle(fontSize: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                      elevation: 5,
                      backgroundColor: Colors.green,
                    ),
                  ),
                  const SizedBox(height: 20), // Extra space at bottom
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
