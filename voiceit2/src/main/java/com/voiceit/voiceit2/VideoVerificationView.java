package com.voiceit.voiceit2;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.loopj.android.http.JsonHttpResponseHandler;
import cz.msebera.android.httpclient.Header;

import org.json.JSONException;
import org.json.JSONObject;

public class VideoVerificationView extends AppCompatActivity implements SensorEventListener {

    private CameraSource mCameraSource = null;
    private CameraSourcePreview mPreview;
    private final File mPictureFile = Utils.getOutputMediaFile(".jpeg");
    private MediaRecorder mMediaRecorder = null;
    private final Handler timingHandler = new Handler();
    private  File file;

    private final String mTAG = "VideoVerificationView";
    private Context mContext;

    private RadiusOverlayView mOverlay;

    private boolean playInstructionalVideo;

    boolean playLivenessTutorial = true;
    private VoiceItAPI2 mVoiceIt2;
    private String mUserId = "";
    private String mContentLanguage = "";
    private String mPhrase = "";
    private boolean mDoLivenessCheck = false;
    private int livenessChallengeFailsAllowed;
    private int mLivenessChallengesNeeded;

    private final int mNeededEnrollments = 3;
    private int mFailedAttempts = 0;
    private final int mMaxFailedAttempts = 3;
    private boolean mContinueVerifying = false;

    private SensorManager sensorManager = null;
    private Sensor lightSensor;

    private boolean livenessSuccess = false;
    private String lcoId = "";
    private String uiLivenessInstruction;
    private List<String> lcoStrings = new ArrayList<String>();
    private List<String> lco= new ArrayList<String>();
    private float challengeTime;
    private final String SCREEN_TYPE = "video_verification";
    private boolean mDoLivenessAudioCheck;


    private void getLivenessData(){
        mVoiceIt2.getInitialLivenessData(mUserId, mContentLanguage, "verification", new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, final JSONObject response) {
                Log.v("response", response.toString());
                try {
                    lcoId = response.getString("lcoId");
                    livenessSuccess = response.getBoolean("success");
                    uiLivenessInstruction = response.getString("uiLivenessInstruction");
                    for(int i = 0; i < response.getJSONArray("lcoStrings").length(); i++ ){
                        lcoStrings.add(response.getJSONArray("lcoStrings").getString(i));
                    }
                    for(int i = 0; i < response.getJSONArray("lco").length(); i++ ){
                        lco.add(response.getJSONArray("lco").getString(i));
                    }
                    challengeTime = response.getInt("livenessChallengeTime");
                    beginVerification();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, final JSONObject errorResponse) {
                exitViewWithMessage("voiceit-failure","Error Getting Liveness Challenge");
            }
        });
    }

    private void releaseMediaRecorder(){
        if(mMediaRecorder!=null){
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            mCameraSource.getCameraInstance().lock();
        }
    }

    public void stopRecording() {
        if(mMediaRecorder!=null && mDoLivenessCheck) {
            mMediaRecorder.stop();
            releaseMediaRecorder();
            mCameraSource.getCameraInstance().lock();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopRecording();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);

        super.onCreate(savedInstanceState);

        // Grab data from parent activity
        Bundle bundle = getIntent().getExtras();
        if(bundle != null) {
            mVoiceIt2 = new VoiceItAPI2(bundle.getString("apiKey"), bundle.getString("apiToken"));
            mUserId = bundle.getString("userId");
            mContentLanguage = bundle.getString("contentLanguage");
            mPhrase = bundle.getString("phrase");
            playLivenessTutorial = bundle.getBoolean("livenessTutorial");
            mDoLivenessCheck = bundle.getBoolean("doLivenessCheck");
            mDoLivenessAudioCheck = bundle.getBoolean("doLivenessAudioCheck");
            livenessChallengeFailsAllowed = bundle.getInt("livenessChallengeFailsAllowed");
            mLivenessChallengesNeeded = bundle.getInt("livenessChallengesNeeded");
            CameraSource.displayPreviewFrame = bundle.getBoolean("displayPreviewFrame");
        }

        // Initialize video file for recording data
        if(mDoLivenessCheck) {
            try {
                createFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Hide action bar
        try {
            this.getSupportActionBar().hide();
        } catch (NullPointerException e) {
            Log.d(mTAG,"Cannot hide action bar");
        }

        // // Set screen brightness to full
        // if(!Utils.setBrightness(this, 255)){
        //     exitViewWithMessage("voiceit-failure","Hardware Permissions not granted");
        // }

        // Set context
        mContext = this;
        // Set content view
        setContentView(R.layout.activity_video_verification_view);
        mPreview = findViewById(R.id.camera_preview);

        // Text output on mOverlay
        mOverlay = findViewById(R.id.overlay);
        CameraSource.mOverlay = mOverlay;

        // Lock orientation
        if (Build.VERSION.SDK_INT >= 18) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            setRequestedOrientation(Utils.lockOrientationCode(getWindowManager().getDefaultDisplay().getRotation()));
        }

        PackageManager pm = getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_LIGHT)) {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor prefEditor = sharedPref.edit();
        playInstructionalVideo = this.playLivenessTutorial && sharedPref.getBoolean("playInstructionalVideo", true);
        if(playInstructionalVideo && mDoLivenessCheck) {
            prefEditor.putBoolean("playInstructionalVideo", false);
            prefEditor.apply();

            Intent intent = new Intent(this, InstructionalVideoView.class);
            bundle = new Bundle();
            bundle.putBoolean("isVideo", true);
            intent.putExtras(bundle);
            this.startActivityForResult(intent, 0);
        }
    }

    private void beginVerification(){
        mContinueVerifying = true;
        // Try to setup camera source
        mCameraSource = Utils.createCameraSource(this, new FaceTrackerFactory(this));
        // Try to start camera
        if(!Utils.startCameraSource(this, mCameraSource, mPreview)){
            exitViewWithMessage("voiceit-failure","Error starting camera");
        } else {
            mVoiceIt2.getAllVideoEnrollments(mUserId, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, Header[] headers, final JSONObject Response) {
                    try {
                        // Check If enough enrollments, otherwise return to previous activity
                        if (Response.getInt("count") < mNeededEnrollments) {
                            mOverlay.updateDisplayText(getString(R.string.NOT_ENOUGH_ENROLLMENTS));
                            // Wait for ~2.5 seconds
                            timingHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    exitViewWithMessage("voiceit-failure", "Not enough enrollments");
                                }
                            }, 2500);
                        } else {
                            if(!mDoLivenessCheck){
                                mOverlay.updateDisplayText(getString(R.string.LOOK_INTO_CAM));
                            } else{
                                mOverlay.updateDisplayText(uiLivenessInstruction);
                            }
                            LivenessTracker.continueDetecting = true;
                        }
                    } catch (JSONException e) {
                        Log.d(mTAG, "JSON exception : " + e.toString());
                    }
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, final JSONObject errorResponse) {
                    if (errorResponse != null) {
                        try {
                            // Report error to user
                            mOverlay.updateDisplayText(getString((getResources().getIdentifier(errorResponse.
                                    getString("responseCode"), "string", getPackageName()))));
                        } catch (JSONException e) {
                            Log.d(mTAG, "JSON exception : " + e.toString());
                        }
                        // Wait for 2.0 seconds
                        timingHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                exitViewWithJSON("voiceit-failure", errorResponse);
                            }
                        }, 2000);
                    } else {
                        Log.e(mTAG, "No response from server");
                        mOverlay.updateDisplayTextAndLock(getString(R.string.CHECK_INTERNET));
                        // Wait for 2.0 seconds
                        timingHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                exitViewWithMessage("voiceit-failure", "No response from server");
                            }
                        }, 2000);
                    }
                }
            });
        }
    }

    private void createFile() throws IOException {
        file = new File(this.getFilesDir() + "/" + File.separator + "video.mp4");
        file.createNewFile();
    }


    private void startVerificationFlow() {
        // get Live-nes Challenges and time
        if(mDoLivenessCheck) {
            getLivenessData();
        } else {
            beginVerification();
        }
    }

    /**
     * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
     * uses this factory to create face trackers as needed -- one for each individual.
     */
    private class FaceTrackerFactory implements MultiProcessor.Factory<Face> {

        private final Activity mActivity;
        private final int [] livenessChallengeOrder = {1, 2, 3};

        private FaceTrackerFactory(VideoVerificationView activity) {
            mActivity = activity;
            LivenessTracker.continueDetecting = false;
            LivenessTracker.livenessChallengesPassed = 0;
            LivenessTracker.livenessChallengeFails = 0;
            Utils.randomizeArrayOrder(livenessChallengeOrder);
        }

        @Override
        public Tracker<Face> create(Face face) {
            return new LivenessTracker(mVoiceIt2, mOverlay, mActivity, new FaceTrackerCallBackImpl(),
                    livenessChallengeOrder, mDoLivenessCheck, mDoLivenessAudioCheck, mPreview, mPhrase,
                    livenessChallengeFailsAllowed, mLivenessChallengesNeeded, uiLivenessInstruction, mUserId,
                    lcoStrings, lco, challengeTime, livenessSuccess, lcoId, mContentLanguage, SCREEN_TYPE, mCameraSource, mMediaRecorder);
        }
    }

    private void requestHardwarePermissions() {
        final int PERMISSIONS_REQUEST_RECORD_AUDIO = 0;
        final int PERMISSIONS_REQUEST_CAMERA = 1;
        final int ASK_MULTIPLE_PERMISSION_REQUEST_CODE = 2;
        // MY_PERMISSIONS_REQUEST_* is an app-defined int constant. The callback method gets the
        // result of the request.
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
                requestPermissions(new String[]{
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CAMERA},
                        ASK_MULTIPLE_PERMISSION_REQUEST_CODE);
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                            PERMISSIONS_REQUEST_RECORD_AUDIO);
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
                            PERMISSIONS_REQUEST_CAMERA);
                }
            }
        } else {
            // Permissions granted, so continue with view
            startVerificationFlow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(mTAG,"Hardware Permissions not granted");
            exitViewWithMessage("voiceit-failure", "Hardware Permissions not granted");

        } else {
            // Permissions granted, so continue with view
            startVerificationFlow();
        }
    }

    private void exitViewWithMessage(String action, String message) {
        Utils.setBrightness(this, Utils.oldBrightness);
        mContinueVerifying = false;
        timingHandler.removeCallbacksAndMessages(null);
        LivenessTracker.livenessTimer.removeCallbacksAndMessages(null);
        stopRecording();
        Intent intent = new Intent(action);
        JSONObject json = new JSONObject();
        try {
            json.put("message", message);
        } catch(JSONException e) {
            Log.d(mTAG,"JSON Exception : " + e.getMessage());
        }
        intent.putExtra("Response", json.toString());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    public void exitViewWithJSON(String action, JSONObject json) {
        Utils.setBrightness(this, Utils.oldBrightness);
        mContinueVerifying = false;
        timingHandler.removeCallbacksAndMessages(null);
        LivenessTracker.livenessTimer.removeCallbacksAndMessages(null);
        stopRecording();
        Intent intent = new Intent(action);
        intent.putExtra("Response", json.toString());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public final void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        if(lux < Utils.luxThreshold) {
            mOverlay.setLowLightMode(true);
        } else {
            mOverlay.setLowLightMode(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Confirm permissions and start enrollment flow
        requestHardwarePermissions();
    }

    @Override
    public void onBackPressed() {
        exitViewWithMessage("voiceit-failure", "User Canceled");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(!playInstructionalVideo || !mDoLivenessCheck) {
            // Confirm permissions and start enrollment flow
            requestHardwarePermissions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(sensorManager != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreview.stop();
        if(sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if(mContinueVerifying) {
            exitViewWithMessage("voiceit-failure", "User Canceled");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    private void takePicture() {

        // Verify after taking picture
        final CameraSource.PictureCallback mPictureCallback = new CameraSource.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data) {
                // Check file
                if (mPictureFile == null) {
                    Log.d(mTAG, "Error creating media file, check storage permissions");
                    return;
                }
                // Write picture to file
                try {
                    FileOutputStream fos = new FileOutputStream(mPictureFile);
                    fos.write(data);
                    fos.close();
                } catch (FileNotFoundException e) {
                    Log.d(mTAG, "File not found: " + e.getMessage());
                } catch (IOException e) {
                    Log.d(mTAG, "Error accessing file: " + e.getMessage());
                }

                // With liveness checks enabled, a picture is taken before it is done
                // and verify is called later
                if(!mDoLivenessCheck) {
                    verifyUser();
                }  else {
                    // Continue liveness detection
                    LivenessTracker.continueDetecting = true;
                }
            }
        };

        try {
            // Take picture of face
            mCameraSource.takePicture(null, mPictureCallback);
        } catch (Exception e) {
            Log.d(mTAG, "Camera exception : " + e.getMessage());
            exitViewWithMessage("voiceit-failure", "Camera Error");
        }
    }

    private void failVerification(final JSONObject response) {

        // Continue showing live camera preview
        mOverlay.setPicture(null);

        mOverlay.setProgressCircleColor(getResources().getColor(R.color.failure));
        mOverlay.updateDisplayText(getString(R.string.VERIFY_FAIL));

        // Wait for ~1.5 seconds
        timingHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // Report error to user
                    if (response.getString("responseCode").equals("PDNM")) {
                        mOverlay.updateDisplayText(getString((getResources().getIdentifier(response.
                                getString("responseCode"), "string", getPackageName())), mPhrase));
                    } else {
                        mOverlay.updateDisplayText(getString((getResources().getIdentifier(response.
                                getString("responseCode"), "string", getPackageName()))));
                    }
                } catch (JSONException e) {
                    Log.d(mTAG,"JSON exception : " + e.toString());
                }
                // Wait for ~4.5 seconds
                timingHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (response.getString("responseCode").equals("PNTE")) {
                                exitViewWithJSON("voiceit-failure", response);
                            }
                        } catch (JSONException e) {
                            Log.d(mTAG,"JSON exception : " + e.toString());
                        }

                        mFailedAttempts++;
                        // User failed too many times
                        if(mFailedAttempts >= mMaxFailedAttempts) {
                            mOverlay.updateDisplayText(getString(R.string.TOO_MANY_ATTEMPTS));
                            // Wait for ~2 seconds
                            timingHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    exitViewWithJSON("voiceit-failure", response);
                                }
                            },2000);
                        } else if (mContinueVerifying) {
                            if(LivenessTracker.lookingAway) {
                                mOverlay.updateDisplayText(getString(R.string.LOOK_INTO_CAM));
                            }
                            // Reset liveness check and try again
                            LivenessTracker.livenessChallengesPassed = 0;
                            LivenessTracker.livenessChallengeFails = 0;
                            LivenessTracker.continueDetecting = true;
                        }
                    }
                }, 4500);
            }
        }, 1500);
    }

    private void verifyUser() {
        if(mContinueVerifying) {

            mOverlay.updateDisplayText(getString(R.string.SAY_PASSPHRASE, mPhrase));
            try {
                // Create file for audio
                final File audioFile = Utils.getOutputMediaFile(".wav");
                if (audioFile == null) {
                    exitViewWithMessage("voiceit-failure", "Creating audio file failed");
                }

                // Setup device and capture audio
                mMediaRecorder = new MediaRecorder();
                Utils.startMediaRecorder(mMediaRecorder, audioFile);

                mOverlay.setProgressCircleColor(getResources().getColor(R.color.progressCircle));
                mOverlay.startDrawingProgressCircle();
                // Record for ~5 seconds, then send data
                // 4800 to make sure recording is not over 5 seconds
                timingHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mContinueVerifying) {
                            stopRecording();

                            mOverlay.updateDisplayText(getString(R.string.WAIT));
                            mVoiceIt2.videoVerification(mUserId, mContentLanguage, mPhrase, audioFile, mPictureFile, new JsonHttpResponseHandler() {
                                @Override
                                public void onSuccess(int statusCode, Header[] headers, final JSONObject response) {
                                    try {
                                         if (response.getString("responseCode").equals("SUCC")) {
                                            mOverlay.setProgressCircleColor(getResources().getColor(R.color.success));
                                            mOverlay.updateDisplayTextAndLock(getString(R.string.VERIFY_SUCCESS));

                                            // Wait for ~2 seconds
                                            timingHandler.postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    audioFile.deleteOnExit();
                                                    mPictureFile.deleteOnExit();

                                                    exitViewWithJSON("voiceit-success", response);
                                                }
                                            }, 2000);

                                            // Fail
                                        } else {
                                            audioFile.deleteOnExit();
                                            mPictureFile.deleteOnExit();
                                            failVerification(response);
                                        }
                                    } catch (JSONException e) {
                                        Log.d(mTAG, "JSON Error: " + e.getMessage());
                                    }
                                }

                                @Override
                                public void onFailure(int statusCode, Header[] headers, Throwable throwable, final JSONObject errorResponse) {
                                    if (errorResponse != null) {
                                        Log.d(mTAG, "JSONResult : " + errorResponse.toString());

                                        audioFile.deleteOnExit();
                                        mPictureFile.deleteOnExit();

                                        try {
                                            if (errorResponse.getString("responseCode").equals("TVER")) {
                                                // Wait for ~2 seconds
                                                timingHandler.postDelayed(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        exitViewWithJSON("voiceit-failure", errorResponse);
                                                    }
                                                }, 2000);
                                            }
                                        } catch (JSONException e) {
                                            Log.d(mTAG, "JSON exception : " + e.toString());
                                        }

                                        failVerification(errorResponse);
                                    } else {
                                        Log.e(mTAG, "No response from server");
                                        mOverlay.updateDisplayTextAndLock(getString(R.string.CHECK_INTERNET));
                                        // Wait for 2.0 seconds
                                        timingHandler.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                exitViewWithMessage("voiceit-failure", "No response from server");
                                            }
                                        }, 2000);
                                    }
                                }
                            });
                        }
                    }
                }, 4800);
            } catch (Exception ex) {
                Log.d(mTAG, "Recording Error: " + ex.getMessage());
                exitViewWithMessage("voiceit-failure", "Recording Error");
            }

        }
    }

    class FaceTrackerCallBackImpl implements LivenessTracker.viewCallBacks { // Implements callback methods defined in FaceTracker interface
        public void authMethodToCallBack() { verifyUser(); }
        public void takePictureCallBack() { takePicture(); }
    }
}
