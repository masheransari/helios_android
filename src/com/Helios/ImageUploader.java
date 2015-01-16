package com.Helios;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;

import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.sqs.AmazonSQS;

/**
 * Display personalized greeting.
 */
public class ImageUploader extends AsyncTask<Void, Void, Boolean> {
    private final String TAG = "Helios_" + getClass().getSimpleName(); 
	protected Context con;

	private String KEY_PREFIX;

	// used to access UI thread for toasts
	private static Handler handler = new Handler(Looper.getMainLooper());
		
	protected String mEmail;
	protected Bitmap img;
	protected String token;
	private Location pic_location;
	private boolean WifiUploadOnly;
	private File video;
	private AmazonS3Client s3Client;
	private AmazonSQS sqsQueue;
	private CognitoHelper cognitoHelperObj;
	
	private String uploadType;
	private BeaconInfo beaconInfo;
	
	ImageUploader(Context con, String email, Bitmap bmp, String tok, Location loc, boolean WifiUploadOnly) {
		this.con = con;		
		this.mEmail = email;
		this.img = bmp;
		this.token = tok;
		this.pic_location = loc;
		this.WifiUploadOnly = WifiUploadOnly;
		uploadType = "IMAGE";
	}
	
	ImageUploader(Context con, File videoFile, CognitoHelper cognitoHelper, Location loc, boolean WifiUploadOnly) {
		// used to upload video file to Amazon S3
		this.con = con;
		this.video = videoFile;
		
		this.cognitoHelperObj = cognitoHelper;
		this.s3Client = cognitoHelper.s3Client;
		this.sqsQueue = cognitoHelper.sqsQueue;
		
		this.WifiUploadOnly = WifiUploadOnly;
		this.pic_location = loc;
		
		uploadType = "VIDEO";
	}

	ImageUploader(Context con, CognitoHelper cognitoHelper, BeaconInfo beaconInfo, Location loc, boolean WifiUploadOnly) {
		// used to upload bluetooth beacon details to Amazon S3
		this.con = con;
		
		this.cognitoHelperObj = cognitoHelper;
		this.s3Client = cognitoHelper.s3Client;
		this.sqsQueue = cognitoHelper.sqsQueue;
		
		this.beaconInfo = beaconInfo;
		this.WifiUploadOnly = WifiUploadOnly;
		this.pic_location = loc;
		
		uploadType = "BEACON";
	}

	protected Boolean doInBackground(Void... params) {
		// no upload if user wants to upload on wifi only and we are not on Wifi
		this.KEY_PREFIX = cognitoHelperObj.getIdentityID();		

		if (WifiUploadOnly && !isWifiConnected()){
			Log.i(TAG, "Upload unsuccessful - not on Wifi");
			displayToast("Upload unsuccessful - not on Wifi", Toast.LENGTH_LONG);
			// TODO: change this so it retries later instead of dropping the video
			removeTempVideoFile();
			return false;
		}
		// we are either on Wifi connection or user is fine with using mobile data
		// so go ahead with the upload
		try {
			if(uploadType.contentEquals("VIDEO")){			
				return uploadVideo();
			}
			
			if(uploadType.contentEquals("BEACON")){
				Log.i(TAG, "Got identity ID " + KEY_PREFIX);			
				
				String key = KEY_PREFIX + "/beacons/" + beaconInfo.getBeaconUniqueKey();
				ObjectMetadata met = new ObjectMetadata();
				met.setContentLength(beaconInfo.toString().length());
				met.setContentType("text/plain");
				InputStream is = new ByteArrayInputStream(beaconInfo.toString().getBytes());				
				PutObjectRequest req = new PutObjectRequest(Config.S3_BUCKET_NAME, key, is, met);
				
				if (pic_location != null){ // add latitude & longitude data if available					
					Log.v(TAG, "Adding lat:" + pic_location.getLatitude() + " Lon:" + pic_location.getLongitude());
					met.addUserMetadata("latitude", Double.toString(pic_location.getLatitude()));
					met.addUserMetadata("longitude", Double.toString(pic_location.getLongitude()));
					req.setMetadata(met);
				}
				
				PutObjectResult putResult = s3Client.putObject(req);
				if (putResult != null)
					sqsQueue.sendMessage(Config.SQS_QUEUE_URL, key);
				Log.i(TAG, "Upload successful");

				return true;
			}
			else{
				Log.i(TAG, "Upload unsuccessful - image upload to S3 not enabled");
				displayToast("Upload unsuccessful - image upload to S3 not enabled", Toast.LENGTH_LONG);
				return false;
			}
		}
		catch (AmazonServiceException ase) {
            onError("AmazonServiceException", ase);
            Log.e(TAG, "Error Message:    " + ase.getMessage());
            Log.e(TAG, "HTTP Status Code: " + ase.getStatusCode());
            Log.e(TAG, "AWS Error Code:   " + ase.getErrorCode());
            Log.e(TAG, "Error Type:       " + ase.getErrorType());
            Log.e(TAG, "Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            onError("Caught an AmazonClientException", ace);
            Log.e(TAG, "Error Message: " + ace.getMessage());
        }
		catch (Exception ex) {
			onError("Following Error occured, please try again. "
					+ ex.getMessage(), ex);
		}
		removeTempVideoFile();
		return false;
	}

	private Boolean uploadVideo() throws AmazonClientException, AmazonServiceException{
		String key = KEY_PREFIX + "/" + video.getName();
		PutObjectRequest req = new PutObjectRequest(Config.S3_BUCKET_NAME, key, video);
		if (pic_location != null){ // add latitude & longitude data if available
			ObjectMetadata met = new ObjectMetadata();
			Log.v(TAG, "Adding lat:" + pic_location.getLatitude() + " Lon:" + pic_location.getLongitude());
			met.addUserMetadata("latitude", Double.toString(pic_location.getLatitude()));
			met.addUserMetadata("longitude", Double.toString(pic_location.getLongitude()));
			req.setMetadata(met);
		}
		PutObjectResult putResult = s3Client.putObject(req);
		if (putResult != null)
			sqsQueue.sendMessage(Config.SQS_QUEUE_URL, key);
		Log.i(TAG, "Upload successful");

		return true;
	}

	protected void onError(String msg, Exception e) {
		if (e != null) {
			Log.e(TAG, "Exception: ", e);
		}
		displayToast(msg, Toast.LENGTH_SHORT);; // will be run in UI thread

	}
	
	private boolean isWifiConnected(){
		ConnectivityManager connectivity = (ConnectivityManager) con.getSystemService(Context.CONNECTIVITY_SERVICE);
        //If connectivity object is not null
        if (connectivity != null) {
            //Get network info - WIFI internet access
            NetworkInfo info = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
 
            if (info != null) {
                //Look for whether device is currently connected to WIFI network
                if (info.isConnected()) {
                    return true;
                }
            }
        }
        return false;
	}
	
	private void displayToast(final String text, final int toast_length){
		// helper method uses the main thread to display a toast
		// we use this because if this class is used by a Service
		// as opposed to an Activity, we can't access the UI thread 
		// in the normal way using RunOnUIThread
		handler.post(new Runnable(){
			public void run(){
				Toast.makeText(con, text, toast_length).show();
			}
		});
	}
	
	private void removeTempVideoFile(){
		// remove temp video file from phone storage
		if(uploadType.contentEquals("VIDEO"))
			video.delete();
	}
}
