package io.adaptivecards.adaptivecardssample;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Network;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.RetryPolicy;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class RemoteClientConnection
{
    private static final String LOG_TAG = "RemoteClientConnection";
    private Observer m_observer;
    private Context m_context;
    private Activity m_activity;
    private PeerConnection m_conn;
    private String m_hostId;
    private String m_answerSdp;

    public RemoteClientConnection(Activity activity, Observer observer)
    {
        this.m_activity = activity;
        this.m_context = activity;
        this.m_observer = observer;
    }

    public interface Observer
    {
        void onConnecting(String status);

        void onConnected();

        void onConnectFailed(String errorMessage);

        void onMessage(String cardPayload);
    }

    public void connect(String hostId)
    {
        m_hostId = hostId;
        RequestQueue queue = Volley.newRequestQueue(m_context);
        String url = "https://signalingserver.azurewebsites.net/api/subscriptions/" + hostId;

        // Request the offer info
        JsonObjectRequest stringRequest = new JsonObjectRequest(Request.Method.GET, url, null, new Response.Listener<JSONObject>()
        {
            @Override
            public void onResponse(JSONObject response)
            {
                connectToHost(response);
            }
        }, new Response.ErrorListener()
        {
            @Override
            public void onErrorResponse(VolleyError error)
            {
                m_observer.onConnectFailed("Is your internet working? Failed to request offer info.");
            }
        });

        // Add the request to the queue
        m_observer.onConnecting("Requesting offer info...");
        queue.add(stringRequest);
    }

    public void disconnect()
    {
        if (m_conn != null)
        {
            m_conn.dispose();
        }

        m_observer = null;
    }

    private void runOnUiThread(Runnable action)
    {
        m_activity.runOnUiThread(action);
    }

    private void connectToHost(final JSONObject offerAndCandidates)
    {
        m_observer.onConnecting("Accepting offer...");

        // Code from https://youtu.be/HS1eKPL4f1o
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(m_context).createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:ws-turn3.xirsys.com").createIceServer());
        String[] turnServers = new String[]
        {
            "turn:ws-turn3.xirsys.com:80?transport=udp",
            "turn:ws-turn3.xirsys.com:3478?transport=udp",
            "turn:ws-turn3.xirsys.com:80?transport=tcp",
            "turn:ws-turn3.xirsys.com:3478?transport=tcp",
            "turns:ws-turn3.xirsys.com:443?transport=tcp",
            "turns:ws-turn3.xirsys.com:5349?transport=tcp"
        };
        for (String turnServer : turnServers)
        {
            iceServers.add(PeerConnection.IceServer.builder(turnServer).setUsername("e-HcX5lqoTEzSQetY5biyT3YzM45GIl3HK4FQuo3y73xvsLAmts_gF8PylqkwBR6AAAAAFyoMKphbGVhZGVy").setPassword("d97cbeee-5827-11e9-bef6-069f0817bf63").createIceServer());
        }
        final ArrayList<IceCandidate> iceCandidates = new ArrayList<>();

        m_conn = factory.createPeerConnection(iceServers, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                // Maybe I need to send these up? http://myhexaville.com/2018/03/19/android-webrtc-signaling/
                // Yeah looks like it: https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/onicecandidate
                Log.i(LOG_TAG, "New candidate");
                iceCandidates.add(iceCandidate);
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.i(LOG_TAG, "DataChannel created");
                final DataChannel currDataChannel = dataChannel;
                dataChannel.registerObserver(new DataChannel.Observer() {
                    @Override
                    public void onBufferedAmountChange(long l) {

                    }

                    @Override
                    public void onStateChange() {
                        Log.i(LOG_TAG, "DataChannel state changed: " + currDataChannel.state());
                        if (currDataChannel.state() == DataChannel.State.OPEN)
                        {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    m_observer.onConnected();
                                }
                            });
                        }
                    }

                    @Override
                    public void onMessage(DataChannel.Buffer buffer) {
                        Log.i(LOG_TAG, "DataChannel message received");
                        try {
                            byte[] dataBytes = new byte[buffer.data.capacity()];
                            buffer.data.get(dataBytes);

                            String dataStr = new String(dataBytes);
                            JSONObject dataMessage = new JSONObject(dataStr);
                            final String cardPayload = dataMessage.getString("cardPayload");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    m_observer.onMessage(cardPayload);
                                }
                            });
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        //runOnUiThread();
                    }
                });
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.i(LOG_TAG, "SignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.i(LOG_TAG, "ConnectionChange: " + iceConnectionState);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE)
                {
                    try {
                        JSONObject answer = new JSONObject();
                        answer.put("sdp", m_conn.getLocalDescription().description);

                        JSONArray candidatesArray = new JSONArray();
                        for (int i = 0; i < iceCandidates.size(); i++)
                        {
                            IceCandidate iceCandidate = iceCandidates.get(i);

                            JSONObject candidate = new JSONObject();
                            candidate.put("candidate", iceCandidate.sdp);
                            candidate.put("sdpMid", iceCandidate.sdpMid);
                            candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);

                            candidatesArray.put(candidate);
                        }

                        answer.put("candidates", candidatesArray);

                        Log.i(LOG_TAG, "Sending answer");
                        sendAnswer(answer);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

            }

            @Override
            public void onAddStream(MediaStream mediaStream) {

            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {

            }

            @Override
            public void onRenegotiationNeeded() {
                Log.i(LOG_TAG, "Renegotiation needed");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

            }
        });

        SessionDescription offerDescription = null;
        try {
            offerDescription = new SessionDescription(SessionDescription.Type.OFFER, offerAndCandidates.getString("sdp"));
        } catch (JSONException e) {
            e.printStackTrace();
            m_observer.onConnectFailed("Invalid JSON");
            return;
        }
        m_conn.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {

            }

            @Override
            public void onSetSuccess() {
                try
                {
                    // Set the candidates (must be done AFTER setting the description)
                    JSONArray candidates = offerAndCandidates.getJSONArray("candidates");

                    for (int i = 0; i < candidates.length(); i++) {
                        JSONObject candidate = candidates.getJSONObject(i);
                        m_conn.addIceCandidate(new IceCandidate(candidate.getString("sdpMid"), candidate.getInt("sdpMLineIndex"), candidate.getString("candidate")));
                    }

                    onSetOfferSucceeded();
                }
                catch (JSONException e)
                {
                    e.printStackTrace();
                    m_observer.onConnectFailed("Invalid JSON");
                    return;
                }
            }

            @Override
            public void onCreateFailure(String s) {
            }

            @Override
            public void onSetFailure(String s) {
                m_observer.onConnectFailed("Setting offer failed");
            }
        }, offerDescription);
    }

    private void sendAnswer(JSONObject answerAndCandidates)
    {
        // Instantiate the RequestQueue.
        RequestQueue queue = Volley.newRequestQueue(m_context);
        String url ="https://signalingserver.azurewebsites.net/api/subscriptions/" + m_hostId;

        // Request a string response from the provided URL. Code from https://stackoverflow.com/questions/33573803/how-to-send-a-post-request-using-volley-with-string-body
        JsonObjectRequest request = new JsonObjectRequestWithEmptyResponse(Request.Method.PUT, url, answerAndCandidates,
            new Response.Listener<JSONObject>() {
                @Override
                public void onResponse(JSONObject response) {
                    // Yay! Nothing to do
                    Log.i(LOG_TAG, "Successfully sent answer to host");
                    openDataChannel();
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    m_observer.onConnectFailed("Are you connected to the internet? Failed to send answer back.");
                }
        });

        // 30 second timeout, no retry
        request.setRetryPolicy(new DefaultRetryPolicy(30 * 1000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        // Add the request to the RequestQueue.
        queue.add(request);
    }

    // https://stackoverflow.com/a/48834515/1454643
    private class JsonObjectRequestWithEmptyResponse extends JsonObjectRequest {
        public JsonObjectRequestWithEmptyResponse(int method, String url, @Nullable JSONObject jsonRequest, Response.Listener<JSONObject> listener, @Nullable Response.ErrorListener errorListener) {
            super(method, url, jsonRequest, listener, errorListener);
        }

        @Override
        public Response<JSONObject> parseNetworkResponse(NetworkResponse response)
        {
            if (response.data.length == 0)
            {
                return Response.success(null, HttpHeaderParser.parseCacheHeaders(response));
            }
            else
            {
                return Response.error(new VolleyError("Unexpected data in response body, expected empty response."));
            }
        }
    }

    private void openDataChannel()
    {
        /*Log.i(LOG_TAG, "Creating data channel");
        DataChannel dataChannel = m_conn.createDataChannel("test", new DataChannel.Init());
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {

            }

            @Override
            public void onStateChange() {
                Log.i(LOG_TAG, "DataChannel state changed");
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                Log.i(LOG_TAG, "DataChannel message received");
            }
        });
        Log.i(LOG_TAG, "Created data channel");*/
        //dataChannel.send()
    }

    private void onSetOfferSucceeded()
    {
        Log.i(LOG_TAG, "Setting offer succeeded");

        MediaConstraints constraints = new MediaConstraints();
        m_conn.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                onCreateAnswerSucceeded(sessionDescription);
            }

            @Override
            public void onSetSuccess() {

            }

            @Override
            public void onCreateFailure(String s) {
                Log.w(LOG_TAG, "Failed to create answer");
            }

            @Override
            public void onSetFailure(String s) {

            }
        }, constraints);
    }

    private void onCreateAnswerSucceeded(SessionDescription sessionDescription)
    {
        Log.i(LOG_TAG, "Creating answer succeeded");

        m_answerSdp = sessionDescription.description;
        m_conn.setLocalDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {

            }

            @Override
            public void onSetSuccess() {
                Log.i(LOG_TAG, "Set local description!");
                // onIceCandidate will be called next
                //m_observer.onConnecting("Set local description!");
            }

            @Override
            public void onCreateFailure(String s) {

            }

            @Override
            public void onSetFailure(String s) {
                Log.w(LOG_TAG, "Failed to set local description: " + s);
                //m_observer.onConnectFailed("Failed to set local description");
            }
        }, sessionDescription);
    }
}
