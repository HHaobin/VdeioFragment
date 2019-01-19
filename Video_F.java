package cn.forsafe.glauncher.fragment;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import cn.forsafe.config.GateMsg;
import cn.forsafe.config.ProvidersConfig;
import cn.forsafe.glauncher.R;
import cn.forsafe.glauncher.util.ProviderFilePathUtil;
import cn.forsafe.glauncher.util.ProviderXMLDataUtil;

/**
 * 视频播放的Fragment
 * 
 * @author yanfa06
 * 
 */
public class Video_F extends BaseFragment {
	private final String TAG = "Video_F";
	private View view;
	private ImageView stop_icon;
	private SurfaceView sv;
	private SurfaceHolder surfaceHolder;
	private MediaPlayer mediaPlayer;
	private String path;
	private File file;
	private int currentPosition = 0;// 视频的播放位置
	private int playFlag = 0;// 播放文件的标记
	private int widthPixels = 0, heightPixels = 0;// 用于保存屏幕宽高
	private int currentWidth = 0, currentHeight = 0;// 用于保存播放器当前宽高
	private int initWidth = 0, initHeight = 0;// 用于保存播放器原始宽高
	private boolean oncePlaying = true;// 判断是否是第一次，或者停止
	private int pTag = 1;// 是否暂停
	private ScheduledExecutorService executorService;// 延迟加载器
	private boolean volumeSwitch = true;// 是否关闭广告声音
	/**
	 * subtime:点击“续播”到暂停时的间隔的和 beginTime：重新回到播放时的bash值 falgTime：点击“播放”时的值
	 * pauseTime：“暂停”时的值
	 */
	private long subtime = 0, beginTime = 0, falgTime = 0, pauseTime = 0;
	private VideoFileReceiver receiver;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		view = LayoutInflater.from(getActivity()).inflate(R.layout.video_fa,
				null);
		init(view);
		initVideo(view);
		return view;
	}

	@Override
	public void onStart() {
		// pause();

		Log.d(TAG, "onStart");
		super.onStart();
	}

	@Override
	public void onResume() {
		Log.d(TAG, "onResume");
		if (adSwitch != Settings.System.getInt(getActivity()
				.getContentResolver(), "forsafe.ad.switch", 0)) {
			changeVideoData();// 视频目录变更
		}
		pause();
		super.onResume();
	}

	@Override
	public void onPause() {
		pause();
		super.onPause();
	}

	@Override
	public void onStop() {
		// pause();
		Log.d(TAG, "onStop");
		super.onStop();
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		super.onDestroy();
		// 注销广播接受者
		getActivity().unregisterReceiver(receiver);
		if (mLocalBroadcastManager!=null) {			
			mLocalBroadcastManager.unregisterReceiver(receiver); 
		}
		// 释放资源
		releaseMedia();
	}

	/**
	 * 初始化
	 * 
	 * @param v
	 */
	private void init(View v) {
		sv = (SurfaceView) view.findViewById(R.id.sv);
		stop_icon = (ImageView) view.findViewById(R.id.stop_icon);

		initListener(v);

		// 为SurfaceHolder添加回调
		surfaceHolder = sv.getHolder();
		surfaceHolder.addCallback(callback);

		// 注册广播
		registerVideoFileReceiver();

		// 获得屏幕宽高的方法,包括虚拟按键高度
		CalculationScreenWH();
	}

	/**
	 * 初始化监听
	 * 
	 * @param v
	 */
	private void initListener(View v) {
		sv.addOnLayoutChangeListener(svLayoutChangeListener);
	}

	/**
	 * 初始化视频播放
	 * 
	 * @param v
	 */
	private void initVideo(View v) {
		getData(true);// 加载文件路径
		executorService = Executors.newScheduledThreadPool(1);
		if (mFiles != null && mFiles.size() != 0) {
			path = mFiles.get(playFlag);
			delayPreparedAndPlay(0);// 延时准备播放的方法
			stop_icon.setVisibility(View.GONE);
		} else {
			stop_icon.setVisibility(View.VISIBLE);
		}
	}

	/** 视频播放器Layout变化监听 */
	private OnLayoutChangeListener svLayoutChangeListener = new OnLayoutChangeListener() {
		@Override
		public void onLayoutChange(View v, int left, int top, int right,
				int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
			// 目的是记录最原始的宽高，用于全屏切换
			if (currentWidth == 0 && currentHeight == 0) {
				// 当前播放器控件的宽高
				currentWidth = right - left;
				currentHeight = bottom - top;
				// 用于记录原始宽高
				initWidth = currentWidth;
				initHeight = currentHeight;
			}
		}
	};
	LocalBroadcastManager mLocalBroadcastManager; 
	/** 注册广播的方法 */
	private void registerVideoFileReceiver() {
		receiver = new VideoFileReceiver();
		// 实例化过滤器并设置要过滤的广播
		IntentFilter filter = new IntentFilter();
		filter.addAction(GateMsg.ACTION_PROVIDER_VIDEO);
		filter.addAction(GateMsg.ACTION_FILE_VIDEO);
		filter.addAction(GateMsg.ACTION_PLAY_VIDEO);
		filter.addAction(GateMsg.ACTION_LEFT_VIDEO);
		filter.addAction(GateMsg.ACTION_RIGHT_VIDEO);
		filter.addAction(GateMsg.ACTION_STOP_VIDEO);
		filter.addAction(GateMsg.ACTION_REPLAY_VIDEO);
		filter.addAction(GateMsg.ACTION_PAUSE_VIDEO);
		filter.addAction(GateMsg.ACTION_VOICE_VIDEO);
		filter.addAction(Intent.ACTION_TIME_TICK);
		getActivity().registerReceiver(receiver, filter);
	}

	private Handler handler = new Handler(new Handler.Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
			case 0:
				play(0);
				break;
			case 1:
				left();
				break;
			case 2:
				right();
				break;
			case 3:
				stop();
				break;
			case 4:
				replay();
				break;
			case 5:
				pause();
				break;
			}
			return false;
		}
	});

	/** 获得屏幕宽高的方法,包括虚拟按键高度 */
	private void CalculationScreenWH() {
		Display display = getActivity().getWindowManager().getDefaultDisplay();
		DisplayMetrics dm = new DisplayMetrics();
		@SuppressWarnings("rawtypes")
		Class c;
		try {
			c = Class.forName("android.view.Display");
			@SuppressWarnings("unchecked")
			Method method = c.getMethod("getRealMetrics", DisplayMetrics.class);
			method.invoke(display, dm);
			heightPixels = dm.heightPixels;// 得到宽度
			widthPixels = dm.widthPixels;// 得到高度
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/** 用于隐藏控制台界面的点击事件 */
	public void launcherHide() {
		if (currentHeight != heightPixels || currentWidth != widthPixels) {
			try {
				// 获得屏幕宽高
				currentHeight = heightPixels;// 得到宽度
				currentWidth = widthPixels;// 得到高度
				changeView();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	};

	/** 用于显示控制台界面的点击事件 */
	public void launcherShow() {
		if (currentHeight != initWidth || currentWidth != initHeight) {
			try {
				// 获得原始宽高
				currentWidth = initWidth;
				currentHeight = initHeight;
				changeView();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	};

	/** 延时准备方法 第一次播放时调用 */
	private void delayPreparedAndPlay(final int msec) {
		executorService.schedule(new Runnable() {
			@Override
			public void run() {
				handler.sendEmptyMessage(0);
			}
		}, 2500000, TimeUnit.MICROSECONDS);
	}

	/** 回调方法 */
	private Callback callback = new Callback() {
		// SurfaceHolder被修改的时候回调
		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			Log.i(TAG, "SurfaceHolder 被销毁");
			// 销毁SurfaceHolder的时候记录当前的播放位置并停止播放
			if (mediaPlayer != null && mediaPlayer.isPlaying()) {
				currentPosition = mediaPlayer.getCurrentPosition();
				mediaPlayer.stop();
			}
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			Log.i(TAG, "SurfaceHolder 被创建");
			if (currentPosition > 0) {
				// 创建SurfaceHolder的时候，如果存在上次播放的位置，则按照上次播放位置进行播放
				if (mFiles != null && mFiles.size() > 0) {
					playFlag--;
					if (playFlag < 0) {
						playFlag = mFiles.size() - 1;
					}
					path = mFiles.get(playFlag);
					play(currentPosition);
				}
				currentPosition = 0;
			}
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width,
				int height) {
			Log.i(TAG, "SurfaceHolder 大小被改变");
		}
	};

	/**
	 * 开始播放
	 * 
	 * @param msec
	 *            播放初始位置
	 */
	protected void play(final int msec) {
		try {
			// 获取视频文件
			file = new File(path);
			// 文件路径处理
			if (!file.exists()) {
				// Toast.makeText(getActivity(), "文件异常：文件路径为空",
				// Toast.LENGTH_SHORT)
				// .show();
				System.out.println("异常路径：" + path);
				if (mFiles != null && mFiles.size() != 0) {
					// 当有文件不存在的时候，判断文件是否所有文件都不存在（如果无该段处理，则会不停的加载文件，一旦文件存在就播放）
					boolean existsTag = false;
					for (int i = 0; i < mFiles.size(); i++) {
						File f = new File(mFiles.get(i));
						if (f.exists()) {
							existsTag = true;
						}
					}
					if (existsTag) {
						// 如果有文件存在，则继续播放
						handler.sendEmptyMessage(2);// 跳转到下一个视频
						getData(false);// 重新获取数据
					} else {
						noFileStatus();// 无文件页面处理
					}
				} else {
					noFileStatus();// 无文件页面处理
				}
				return;
			} else {
				System.out.println("播放路径" + playFlag + "：" + path);
			}
			// 文件路径无问题后开始播放逻辑
			releaseMedia();// 每次播放前都释放资源
			// sv.setVisibility(View.GONE);// 原因是如果播放的视频码流有误，使Media Server Died
			// 这时如果重新释放MediaPlayer并创建，有时会出现错误，所以需要隐藏再创建对象再显示
			mediaPlayer = new MediaPlayer();// 创建对象或者重新创建对象
			mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);// 声音设置
			VolumeSwitch();// 是否关闭广告声音
			mediaPlayer.setDataSource(file.getAbsolutePath());// 设置播放的视频源
			Log.i(TAG, "开始装载");
			mediaPlayer.prepareAsync();// 准备装载
			changeView();// 这里先改变了大小再设置surfaceHolder
			// sv.setVisibility(View.VISIBLE);// 放在changeView
			// 视频跳转会比较快，可是异常视频时解决会有影响
			if (surfaceHolder == null) {
				// 防止java.lang.IllegalArgumentException: The surface has been
				// released
				surfaceHolder = sv.getHolder();
				surfaceHolder.addCallback(callback);
			}
			if(surfaceHolder != null)
				mediaPlayer.setDisplay(surfaceHolder);// 设置显示视频的SurfaceHolder

			mediaPlayer.setOnPreparedListener(new OnPreparedListener() {
				@Override
				public void onPrepared(final MediaPlayer mp) {
					// 视频准备好的回调
					Log.i(TAG, "装载完成");
					if (mediaPlayer.isPlaying()) {
						mediaPlayer.pause();
					}
					mediaPlayer.start();// 开始播放，必须先做准备
					// 按照初始位置播放
					mediaPlayer.seekTo(msec);// 设置播放的初始位置
					falgTime = SystemClock.elapsedRealtime();
					pauseTime = 0;
					savePlayLog();// 存储播放记录
				}
			});
			mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
				@Override
				public void onCompletion(MediaPlayer mp) {
					// 在播放完毕被回调
					handler.sendEmptyMessage(2);// 播放下一个
				}
			});

			mediaPlayer.setOnErrorListener(new OnErrorListener() {

				@Override
				public boolean onError(MediaPlayer mp, int what, int extra) {
					// if (what==900&&extra==0) {
					// //如果出现异常就删除文件
					// file.delete();
					// }
					// 播放时发生错误
					Toast.makeText(getActivity(), "视频文件播放异常",
							Toast.LENGTH_SHORT).show();
					// 先释放，避免影响下一个视频播放
					mp.reset();
					mp.release();
					mp = null;
					System.gc();
					System.out.println(file.getAbsolutePath());
					handler.sendEmptyMessage(2);
					return false;
				}
			});
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (Exception e) {
			Toast.makeText(getActivity(), "异常状态", Toast.LENGTH_SHORT).show();
			e.printStackTrace();
			handler.sendEmptyMessage(2);// 播放下一个，这里如果出现异常，比如文件错误等，直接播放下一个
		}
		stop_icon.setVisibility(View.GONE);
		oncePlaying = false;
		pTag = 1;

	}

	/** 释放mediaPlayer播放对象 */
	public void releaseMedia() {
		if (mediaPlayer != null) {
			try {
				if (mediaPlayer.isPlaying()) {
					mediaPlayer.stop();
				}
				mediaPlayer.release();
				mediaPlayer = null;
				System.gc();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/** 播放上一个视频 */
	private void left() {
		if (mFiles != null && mFiles.size() > 0) {
			playFlag--;
			if (playFlag < 0) {
				playFlag = mFiles.size() - 1;
			}
			path = mFiles.get(playFlag);
			delayPreparedAndPlay(0);
		}
	}

	/** 播放下一个视频 */
	private void right() {
		if (mFiles != null && mFiles.size() > 0) {
			playFlag++;
			if (mFiles.size() <= playFlag) {
				playFlag = 0;
			}
			path = mFiles.get(playFlag);
			delayPreparedAndPlay(0);
		}
	}

	/** 停止播放 */
	public void stop() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
			mediaPlayer.release();
			mediaPlayer = null;
			// surfaceHolder.getSurface().release();
			// surfaceHolder = null;
			pTag = 0;
			oncePlaying = true;
			falgTime = 0;
			subtime = 0;
			stop_icon.setVisibility(View.VISIBLE);
		}
	}

	/** 重新开始播放 */
	public void replay() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.seekTo(0);
			Toast.makeText(getActivity(), "重新播放", Toast.LENGTH_SHORT).show();
			return;
		}
		falgTime = SystemClock.elapsedRealtime();
		subtime = 0;
		play(0);
	}
	private int pausePosition=0;//记录暂停的进度
	/** 暂停或继续 */
	public void pause() {
		if (mediaPlayer != null) {
			if (pTag == 0) {
				
				
				delayPreparedAndPlay(0);
				executorService.schedule(new Runnable() {
					@Override
					public void run() {
						mediaPlayer.seekTo(pausePosition);
					}
				}, 2800000, TimeUnit.MICROSECONDS);
				stop_icon.setVisibility(View.GONE);
				// Toast.makeText(getActivity(), "继续播放", Toast.LENGTH_SHORT)
				// .show();
				pTag = 1;
				subtime += SystemClock.elapsedRealtime() - pauseTime;
				beginTime = falgTime + subtime; 
				return;
			} else if (mediaPlayer.isPlaying()) {
				pausePosition=mediaPlayer.getCurrentPosition();
				mediaPlayer.pause();
				stop_icon.setVisibility(View.VISIBLE);
				// Toast.makeText(getActivity(), "暂停播放", Toast.LENGTH_SHORT)
				// .show();
				pTag = 0;
				pauseTime = SystemClock.elapsedRealtime(); 
			}
		}
	}

	/** 无文件时或者文件列表为空的是否显示图片 */
	private void noFileStatus() {
		releaseMedia();// 取消Media
		oncePlaying = true;// 重回一次未播放的状态
		stop_icon.setVisibility(View.VISIBLE);// 停止时图片显示
	}

	/**
	 * 
	 * 视频宽度适配
	 * 
	 * 每次重新播放视频或者全屏切换时都进行适配
	 */
	@SuppressLint("NewApi")
	private void changeView() {
		if (path != null) {
			MediaMetadataRetriever retr = new MediaMetadataRetriever();
			retr.setDataSource(path);
			Bitmap bm = retr.getFrameAtTime();
			retr.release();
			if (null == bm)
				return;
			int mVideoWidth = bm.getWidth();
			int mVideoHeight = bm.getHeight();
			// Log.i(TAG, "播放器宽度" + currentWidth + "播放器高度" + currentHeight
			// + "视频宽度" + mVideoWidth + "视频高度" + mVideoHeight);
			RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) sv
					.getLayoutParams();
			if (mVideoWidth * currentHeight >= currentWidth * mVideoHeight) {// 横向视频
				// 视频大小改变
				params = new RelativeLayout.LayoutParams(
						LayoutParams.MATCH_PARENT, currentWidth * mVideoHeight
								/ mVideoWidth);
				params.addRule(RelativeLayout.CENTER_IN_PARENT);
				sv.setLayoutParams(params);
			} else if (mVideoWidth * currentHeight < currentWidth
					* mVideoHeight) {// 纵向视频
				// 视频大小改变
				params = new RelativeLayout.LayoutParams(currentHeight
						* mVideoWidth / mVideoHeight, LayoutParams.MATCH_PARENT);
				params.addRule(RelativeLayout.CENTER_IN_PARENT);
				sv.setLayoutParams(params);
			}
		}
	}

	/** 选择是否关闭广告声音 */
	public void VolumeSwitch() {
		if (volumeSwitch) {
			OpenVolume();
		} else {
			CloseVolume();
		}
	}

	/** 关闭广告声音 */
	public void CloseVolume() {
		mediaPlayer.setVolume(0, 0);
	}

	/** 开启广告声音 */
	public void OpenVolume() {
		AudioManager audioManager = (AudioManager) getActivity()
				.getSystemService(Service.AUDIO_SERVICE);
		mediaPlayer.setVolume(0, 0);
		mediaPlayer.setVolume(
				audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM),
				audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM));
	}

	private ArrayList<String> mFiles = null;
	private ArrayList<Integer> mFilesId = null;
	private ProviderFilePathUtil mProviderFilePathUtil;
	private int adSwitch;// 广告数据开关

	/** 獲得數據的方法 */
	@SuppressLint("SdCardPath")
	private void getData(boolean toZero) {
		adSwitch = Settings.System.getInt(getActivity().getContentResolver(),
				"forsafe.ad.switch", 0);
		if (adSwitch != 0) {
			mFiles = new ArrayList<String>();
			mFilesId = new ArrayList<Integer>();
			try {
				// 读取数据库获得数据
				mProviderFilePathUtil = new ProviderFilePathUtil(getActivity());
				mProviderFilePathUtil.ProviderFilePath(mFiles, mFilesId);
			} catch (Exception e) {

			}
			if (mFiles.size() > 0 && toZero) {
				// 决定是否需要调到第一个位置
				playFlag = 0;
			}
		} else {
			mFiles = null;
			mFilesId = null;
		}
	}

	private Uri uri = Uri.parse(ProvidersConfig.URI_ADVLOG_LIST);

	/** 存储播放记录 */
	private void savePlayLog() {
		int filesId = 0;
		if (mFilesId != null && mFilesId.size() > 0) {
			filesId = mFilesId.get(playFlag);
		}
		ContentResolver cr = getActivity().getContentResolver();
		ContentValues cv = new ContentValues();
		cv.put("_id", 1);
		cv.put("adv_file_id", filesId);
		cv.put("play_time", (int) (System.currentTimeMillis() / 1000));
		cr.insert(uri, cv);
	}

	/** 改变视频目录 */
	private void changeVideoData() {
		getData(true);
		if (mFiles != null && mFiles.size() != 0) {
			// 有数据，播放视频
			if (mediaPlayer != null && mediaPlayer.isPlaying()) {
				// 此时不阻止视频继续播放,只更新数据
			} else {
				// 更新数据后，开始播放
				if (mFiles.size() <= playFlag) {
					playFlag = 0;
				}
				path = mFiles.get(playFlag);
				delayPreparedAndPlay(0);
			}
			System.out.println("File number：" + mFiles.size());
		} else {
			// 无数据，显示图片
			releaseMedia();
			// btn_pause.setBackgroundResource(R.drawable.surface_stop);
			stop_icon.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * 内部广播接收类
	 * 
	 * @author yanfa06
	 * 
	 */
	public class VideoFileReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			// 是否需要播放广告，1：播放 0：不播放
			int nShowAd = android.provider.Settings.System.getInt(getActivity().getContentResolver(), "forsafe.ad.switch", 0);
			if(nShowAd == 0)
				return;
			System.out.println("收到广播：" + intent.getAction());
			if (intent.getAction().equals(GateMsg.ACTION_PROVIDER_VIDEO)) {
				Log.i(TAG, "解析XML文件，数据库变更");
				new Thread(new Runnable() {
					public void run() {
						new ProviderXMLDataUtil(getActivity())
								.changeProviderData();
						changeVideoData();
						System.out.println("更新");
					}
				}).start();
			} else if (intent.getAction().equals(Intent.ACTION_TIME_TICK)) {
				Log.i(TAG, "时间监听，视频目录文件变更");
				Date date = new Date();
				SimpleDateFormat df = new SimpleDateFormat("mm");
				if (df.format(date).equals("00")) {
					changeVideoData();
					System.out.println("更新");
				} else {
					System.out.println("不更新");
				}
			} else if (intent.getAction().equals(GateMsg.ACTION_FILE_VIDEO)) {
				Log.i(TAG, "视频目录文件变更");
				changeVideoData();
			} else if (intent.getAction().equals(GateMsg.ACTION_PLAY_VIDEO)) {
				Log.i(TAG, "播放视频");
				handler.sendEmptyMessage(0);// 播放视频
			} else if (intent.getAction().equals(GateMsg.ACTION_LEFT_VIDEO)) {
				Log.i(TAG, "播放上一个视频");
				handler.sendEmptyMessage(1);// 播放上一个
			} else if (intent.getAction().equals(GateMsg.ACTION_RIGHT_VIDEO)) {
				Log.i(TAG, "播放下一个视频");
				handler.sendEmptyMessage(2);// 播放下一个
			} else if (intent.getAction().equals(GateMsg.ACTION_STOP_VIDEO)) {
				Log.i(TAG, "停止播放视频");
				handler.sendEmptyMessage(3);// 停止播放视频
			} else if (intent.getAction().equals(GateMsg.ACTION_REPLAY_VIDEO)) {
				Log.i(TAG, "重新播放视频");
				handler.sendEmptyMessage(4);// 重新播放视频
			} else if (intent.getAction().equals(GateMsg.ACTION_PAUSE_VIDEO)) {
				Log.i(TAG, "暂停播放视频");
				handler.sendEmptyMessage(5);// 暂停播放视频
			} else if (intent.getAction().equals(GateMsg.ACTION_VOICE_VIDEO)) {
				Log.i(TAG, "广告声音控制");
				volumeSwitch = intent.getBooleanExtra("voice", true);
				if (mediaPlayer != null) {
					VolumeSwitch();
				}
			}
		}
	}
}
