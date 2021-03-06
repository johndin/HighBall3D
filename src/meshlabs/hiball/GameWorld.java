package meshlabs.hiball;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import meshlabs.hiball.levels.ILevel;
import meshlabs.hiball.levels.Level1;
import meshlabs.hiball.objects.BlobShadow;
import meshlabs.hiball.objects.Marble;
import meshlabs.hiball.R;
import android.app.Activity;
import android.os.SystemClock;
import android.util.FloatMath;
import android.util.Log;

import com.google.analytics.tracking.android.GoogleAnalytics;
import com.google.analytics.tracking.android.Tracker;
import com.threed.jpct.Camera;
import com.threed.jpct.CollisionListener;
import com.threed.jpct.Config;
import com.threed.jpct.FrameBuffer;
import com.threed.jpct.Light;
import com.threed.jpct.Loader;
import com.threed.jpct.Matrix;
import com.threed.jpct.Object3D;
import com.threed.jpct.Polyline;
import com.threed.jpct.SimpleVector;
import com.threed.jpct.Texture;
import com.threed.jpct.TextureManager;
import com.threed.jpct.World;
import com.threed.jpct.util.MemoryHelper;

/**
 * Big monolithic class with game logic for camera movement, rendering, and a 
 * bunch of other stuff. Per-level specifics should be in the Level object and most 
 * of the physics lives in Marble. 
 *
 */
public class GameWorld {
	public final static String TAG = "GameWorld";
	
	public Activity activity;
	protected RendererImpl renderer;
	private World graphicsWorld;
	private HudPrinter hudPrinter;
	public ScoringHandler scoringHandler;

	final public SimpleVector gravity = new SimpleVector(0, 6, 0);
	final public SimpleVector down = new SimpleVector(0, 1, 0);
	private Marble marble;
	public Camera camera;
	private ILevel level;
	private final Set<Object3D> staticObjects = new HashSet<Object3D>();
	private CollisionListener staticCollisionListener;

	public GameState state;

	private SimpleVector cameraPos = new SimpleVector(); // to reduce allocations

	private float cameraDistance = 0.4f;	// a value in [0,1]
	private float cameraAngle = 0; 	// Angle around the y axis, 0= looking toward -x
	
	private long last2FingerTimestamp = 0;
	
	public GameWorld(Activity parent) {
		this.activity = parent;
		this.renderer = new RendererImpl(this);
		
		this.state = new GameState();
		
		this.graphicsWorld = new World();
		this.hudPrinter = new HudPrinter(state);
		this.scoringHandler = new ScoringHandler(this);
		
		initializeWorld();
	}
	
	// angle and magnitude of force vector on the screen. turns into world coords for the marble. 
	public void applyForce(float screenPathAngle, float screenPathMagnitude) {
		marble.setForce(cameraAngle + 3.141592f/2 -screenPathAngle, screenPathMagnitude);
	}
	
	// in normalized screen coords. 
	public void moveCamera(float dYaw, float dDistance) {
		last2FingerTimestamp = SystemClock.uptimeMillis();
		cameraAngle += dYaw;
		
		float sumCamDist = cameraDistance + dDistance;
		
		//Log.i("MoveCamera", "Distance "+sumCamDist+"  after trying to add "+dDistance);
		if (sumCamDist > 0 &&  sumCamDist < 1) {
			cameraDistance = sumCamDist;
		} 
	}
	
	private final static float CAMERA_MIN_DISTANCE=2f;
	private final static float CAMERA_MAX_DISTANCE=15f;
	private final static float CAMERA_MAX_ANGLE=0.8f; // Fraction of 100% vertical

	private void pointCameraSmart() {
		float adjustedCameraDistance = CAMERA_MIN_DISTANCE+cameraDistance*(CAMERA_MAX_DISTANCE-CAMERA_MIN_DISTANCE);
		
		float heightAngle = (0.5f - state.marblePosition.y / 50);
		if (heightAngle > CAMERA_MAX_ANGLE) {
			heightAngle = CAMERA_MAX_ANGLE;
		}
		heightAngle = heightAngle * 3.14159f/2;
		float xPos = adjustedCameraDistance*FloatMath.cos(heightAngle);
		
		float yPos = -adjustedCameraDistance*FloatMath.sin(heightAngle);
		
		cameraPos.set(xPos, yPos, 0);
		//Log.i("Camera", "Setting camera pos to "+cameraPos+" relative");
		cameraPos.add(state.marblePosition);
		camera.setPosition(cameraPos);
		camera.lookAt(state.marblePosition);
	}
	
	/**
	 * Factor by which (physics) time is slowed down. Used for slowing down time when zooming in/out
	 * with 2 fingers.
	 */
	private float calcTimeScaleFactor() {
		long timeSinceFrozen = SystemClock.uptimeMillis() - last2FingerTimestamp;
		float timeScaleFactor = 1;
		
		if (timeSinceFrozen < 100) {
			timeScaleFactor = 0.25f;
		}
		
		return timeScaleFactor;
	}
	
	
	// The main method that gets called each game loop. TimeStep is in ms.
	public void updateGame(float timeStep) {
		if (level.isOutsideBoundaries(marble.getTransformedCenter())) {		// Death sequence
			boolean finished = marble.deathSequence(timeStep);
			if (finished) { 
				Tracker tracker = GoogleAnalytics.getInstance(activity).getDefaultTracker();
				tracker.sendEvent("Gameplay", "Level1", "Death", (long) 1);
				
				marble.resetState(level.getStartingBallPosition());
				cameraAngle = level.getStartingCameraAngle();
			}
		} else {
			float timeScaleFactor = calcTimeScaleFactor();
			marble.timeStep(timeStep*timeScaleFactor);
			
			scoringHandler.timeStep(timeStep);
		}
		pointCameraSmart();
	}
	
	public void onResume() {
		renderer.timeSmoother.initialize(17.5f);
	}
	
	public void renderAndDraw(FrameBuffer fb) {
		graphicsWorld.renderScene(fb);
		graphicsWorld.draw(fb);
		
		scoringHandler.draw(fb);
		hudPrinter.printHud(fb);
	}
	
	public void resyncRenderer() {
		reloadTextures();
	}
	
	/**
	 * Called after we are done using the GameWorld.
	 */
	public void onDestroy() {
		graphicsWorld.dispose();
	}
	
	/**
	 * Populates the JPCT world. Safe to call multiple times? Shouldn't need to be.
	 */
	private void initializeWorld() {
		setJPCTConfig();
		graphicsWorld.setAmbientLight(120, 120, 120); 	// Default 100,100,100
		
		Light sun = new Light(graphicsWorld);
		sun.setIntensity(180, 180, 180);
		SimpleVector sv = new SimpleVector(0f, -200f, 0f);
		sun.setPosition(sv);
		
		reloadTextures();
		
		level = new Level1(this);
		
		marble = new Marble(this, 0.5f);
		marble.resetState(level.getStartingBallPosition());
		staticCollisionListener = new CollisionHandler(marble);
		for (Object3D obj : staticObjects) {
			obj.addCollisionListener(staticCollisionListener);
		}
		
		cameraAngle = level.getStartingCameraAngle();
		camera = graphicsWorld.getCamera();
		
		long time = SystemClock.uptimeMillis();
		scoringHandler.warmup();
		time = SystemClock.uptimeMillis() - time;
		
		MemoryHelper.compact();
	}
	
	private void setJPCTConfig() {
		//Config.collideOffset = 10;
		Config.glTransparencyMul = 0.0039f;	// Set transparency in 0-255
		Config.glTransparencyOffset = 0.0039f;
	}
	
	public void reloadTextures() {
		reloadTextureResource(R.raw.ball3, false, Marble.TEXTURE);
		reloadTextureResource(R.raw.wood_texture, false, Level1.MAP_TEXTURE);
		reloadTextureResource(R.raw.shadow_noalpha, false, BlobShadow.TEXTURE);
	}
	
	public void reloadTexture(String textureName, Texture texture) {
		if (TextureManager.getInstance().containsTexture(textureName)) {
			//TextureManager.getInstance().removeAndUnload(textureName, renderer.getFrameBuffer());
			return;
		}
		TextureManager.getInstance().addTexture(textureName, texture);
	}
	
	public void reloadTextureResource(int resourceID, boolean useAlpha, String textureName) {
		Texture tex = new Texture(activity.getResources().openRawResource(resourceID), useAlpha);
		reloadTexture(textureName, tex);
	}
	
	public Object3D load3DS(String filename, float scale) {
		Object3D[] model;
		InputStream is;
		try {
			is = activity.getAssets().open(filename);
		} catch (IOException e) {
			Log.e("GameWorld", "Unable to open asset "+filename);
			e.printStackTrace();
			return null;
		}
		model = Loader.load3DS(is, scale);
		
		try {
			is = activity.getAssets().open(filename);
		} catch (IOException e) {
			Log.e("GameWorld", "Unable to open asset "+filename);
			e.printStackTrace();
			return null;
		}
		String[] texs = Loader.readTextureNames3DS(is);
		for (int i = 0; i<texs.length; i++) {
			//Log.i("ModelLoader", "Contains texture: "+texs[i]);
		}
		
		for (int i = 0; i<model.length; i++) {
			model[i].setCenter(SimpleVector.ORIGIN);
			model[i].rotateX((float)(-.5*Math.PI));
			model[i].rotateMesh();
			model[i].setRotationMatrix(new Matrix());
		}
		Object3D obj3d = Object3D.mergeAll(model);
		obj3d.calcNormals();
		obj3d.build();
		return obj3d;
	}
	
	public void addStatic(Object3D obj) {
		staticObjects.add(obj);
		graphicsWorld.addObject(obj);
		if (staticCollisionListener != null) {
			obj.addCollisionListener(staticCollisionListener);
		}
	}

	public void addObject(Object3D obj) {
		graphicsWorld.addObject(obj);
	}
	
	public void addPolyline(Polyline obj) {
		graphicsWorld.addPolyline(obj);
	}
	

	public void printStatus() {
		Log.i(TAG, "Ball@"+state.marblePosition+" fps="+state.fps);
	}

}
