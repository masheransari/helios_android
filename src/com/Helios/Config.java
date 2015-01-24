package com.Helios;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;

final class Config {
	static final String ACCOUNT_ID = "886472035129";
	private static final String SERVICE_ACCOUNT_ID = "754068036571-v8j0kf4tivmh252qn3e45uk2fbcu4ktv.apps.googleusercontent.com";

	static final String IDENTITY_POOL = "us-east-1:a41f8041-3c79-4b62-974a-362cffac8301";
	
	static final Regions COGNITO_REGION = Regions.US_EAST_1;
	
	static final String SQS_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/" + ACCOUNT_ID + "/helios";
	static final Region SQS_QUEUE_REGION = Region.getRegion(Regions.US_EAST_1);
			
	// scope string for web component to authenticate with Google via Cognito
	static final String SCOPE = "audience:server:client_id:" + SERVICE_ACCOUNT_ID;
	static final String S3_BUCKET_NAME = "helios-smart";
	
	static long UPLOAD_INTERVAL = 75000;
	static long MAX_VIDEO_FILE_SIZE = 50000000;
	
	// parameters to monitor Bluetooth beacons
	static final String PROXIMITY_UUID = "f7826da6-4fa2-4e98-8024-bc5b71e0893e"; 
	static final String BEACON_FOLDER = "beacons";
	static final String BEACON_LIST = "/" + BEACON_FOLDER + "/beacons.txt";
	
	static boolean WiFiUploadOnly = true;

}
