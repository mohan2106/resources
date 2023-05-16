public class Compressor {
    private static volatile Compressor videoEncoder = null;
    private static CompressorCallback compressorCallback;
    private static CompressorHandler compressorHandler;
    private Context context;

    private Compressor(Context context){
        this.context = context;
        compressorHandler = new CompressorHandler(context.getMainLooper(),compressorCallback);
    };

    public static Compressor getInstance(Context context, CompressorCallback compressorCallback2){
        compressorCallback = compressorCallback2;

        if(videoEncoder == null){
            synchronized (Compressor.class){
                if(videoEncoder == null){
                    videoEncoder = new Compressor(context);
                }
            }
        }else{
            compressorHandler = new CompressorHandler(context.getMainLooper(),compressorCallback);
        }
        return videoEncoder;
    }

    private static final String TAG = Compressor.class.getSimpleName();
    private String OUTPUT_VIDEO_MIME_TYPE = "video/hevc"; // H.265 Advanced Video Coding
    private String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    /** The file path used as the input file. */
    private String inputFilePath;
    /** The destination file for the encoded output. */
    private String outputFilePath;

    private Metadata outputMetadata;
    private Metadata originalMetadata;
    private List<TrackInformation> trackInformationList;
    private ExecutorService executorService;
    private Muxer muxer;

    /**
     * This function will take input file and output file path and compress the video on default Metadata.
     * @param inputFile
     * @param outputFile
     * @throws Throwable
     */
    public void Compress(@NonNull  String inputFile,@NonNull String outputFile) throws Throwable {
        if(inputFile == null || outputFile == null){
            compressorHandler.SendMessage(KEY_ERROR,"Please provide valid Input");
            return;
        }
        setSource(inputFile);
        setOutputFile(outputFile);
        MediaMetadataUtils mediaMetadataUtils = new MediaMetadataUtils(inputFile);
        this.originalMetadata = mediaMetadataUtils.getMetadata();
        this.outputMetadata = getDefaultMetadata();
        Compress();
    }

    /**
     *
     * @param inputFile
     * @param outputFile
     * @param motionFactor this should be between 1.0 to 2.0 depending on motion in video [1.0 for less motion, 1.5 for moderate and 2.0 for high motion]
     * @throws Throwable
     */
    public void Compress(@NonNull String inputFile,@NonNull String outputFile,double motionFactor) throws Throwable {
        setSource(inputFile);
        setOutputFile(outputFile);
        MediaMetadataUtils mediaMetadataUtils = new MediaMetadataUtils(inputFile);
        this.originalMetadata = mediaMetadataUtils.getMetadata();
        DefaultMetadata defaultMetadata = new DefaultMetadata(originalMetadata);
        defaultMetadata.setMotionFactor(motionFactor);
        this.outputMetadata = defaultMetadata.getOutputMetadata();
        if(this.outputMetadata == null){
            compressorHandler.SendMessage(KEY_ERROR,"Video is already compressed");
            return;
        }
        Compress();
    }

    /**
     * This overloaded function will compress the video using resolution.
     * @param inputFile
     * @param outputFile
     * @param resolution
     * @throws Throwable
     */
    public void Compress(@NonNull String inputFile,@NonNull String outputFile, Resolution resolution) throws Throwable {
        if(resolution == null){
//            compressorHandler.SendMessage(KEY_ERROR,"Please provide valid Input");
            Compress(inputFile,outputFile);
            return;
        }
        setSource(inputFile);
        setOutputFile(outputFile);
        MediaMetadataUtils mediaMetadataUtils = new MediaMetadataUtils(inputFile);
        this.originalMetadata = mediaMetadataUtils.getMetadata();
        ConstraintMetadata constraintMetadata = new ConstraintMetadata(originalMetadata);
        this.outputMetadata = constraintMetadata.getMetadataFromResolution(resolution);
        if(this.outputMetadata == null){
            compressorHandler.SendMessage(KEY_ERROR,"Can't compress at this resolution");
            return;
        }
        Compress();
    }

    /**
     * This overloaded function will compress the video using resolution.
     * @param inputFile
     * @param outputFile
     * @param fileSize
     * @throws Throwable
     */
    public void Compress(@NonNull String inputFile,@NonNull String outputFile, long fileSize) throws Throwable {
        if(fileSize <= 0){
            compressorHandler.SendMessage(KEY_ERROR,"File Size can not be less than zero");
            return;
        }
        setSource(inputFile);
        setOutputFile(outputFile);
        MediaMetadataUtils mediaMetadataUtils = new MediaMetadataUtils(inputFile);
        this.originalMetadata = mediaMetadataUtils.getMetadata();
        ConstraintMetadata constraintMetadata = new ConstraintMetadata(originalMetadata);
        this.outputMetadata = constraintMetadata.getMetadataForFileSizeConstraint(fileSize);
        if(this.outputMetadata == null){
            compressorHandler.SendMessage(KEY_ERROR,"Can't compress at this resolution");
            return;
        }
        Compress();
    }

    /**
     * User can set parameters of outputMetadata and send it to compressor
     * @param inputFile
     * @param outputFile
     * @param outputMetadata
     * @throws Throwable
     */
    public void Compress(@NonNull String inputFile,@NonNull String outputFile, @NonNull Metadata outputMetadata) throws Throwable{
        setSource(inputFile);
        setOutputFile(outputFile);
        MediaMetadataUtils mediaMetadataUtils = new MediaMetadataUtils(inputFile);
        this.originalMetadata = mediaMetadataUtils.getMetadata();
        if(outputMetadata == null){
            this.outputMetadata = getDefaultMetadata();
        }else{
            this.outputMetadata = outputMetadata;
        }
        Compress();
    }
    /**
     * private function for compressing the video.
     * @throws Throwable
     */
    private void Compress() throws Throwable {

        filterOutputMetadata();
        //setting output video and audio mime type
        setOutputVideoAudioMimeType();
        handleRotationOfMedia();
        compressorHandler.SendMessage(KEY_STARTED,true);

        originalMetadata.setFileLocation(inputFilePath);
        trackInformationList = new ArrayList<>();

        MediaExtractor extractor = MediaExtractorUtils.createExtractor(inputFilePath);
        int trackCount  =  createTrackInformationList(extractor);
        long expectedOutputFileSize = getExpectedOutputFileSize(outputMetadata);
        muxer = new Muxer(outputFilePath,trackCount,compressorHandler,expectedOutputFileSize, context);

        executorService =  Executors.newFixedThreadPool(trackCount);

        for(TrackInformation trackInformation: trackInformationList){
            Runnable trackCompressor = new CompressTrack(trackInformation,compressorHandler,originalMetadata,outputMetadata,muxer);
            LogMessage("Compressing track : ");
            executorService.execute(trackCompressor);
        }
        executorService.shutdown();
    }

    private Metadata getDefaultMetadata(){
        DefaultMetadata defaultMetadata = new DefaultMetadata(originalMetadata);
        return defaultMetadata.getOutputMetadata();
    }

    private void setOutputVideoAudioMimeType(){
        //checking if developer has set the output video mime type and whether that is supported by device or not
        String outputMimeType = outputMetadata.getVideoMimeType();
        MediaCodecInfo info = CodecUtils.selectCodec(outputMimeType);
        if(info != null){
            //Developer has set the mime type and codec supporting that mime type is found
            OUTPUT_VIDEO_MIME_TYPE = outputMimeType;
        }else{
            OUTPUT_VIDEO_MIME_TYPE = CodecUtils.findBestVideoCodecMimeType();
            outputMetadata.setVideoMimeType(OUTPUT_VIDEO_MIME_TYPE);
        }

        //checking if developer has set the output audio mime type and whether that is supported by device or not
        String audioMimeType = outputMetadata.getAudioMimeType();

        info = CodecUtils.selectCodec(audioMimeType);
        if(info != null){
            //Developer has set the mime type and codec supporting that mime type is found
            OUTPUT_AUDIO_MIME_TYPE = audioMimeType;
        }else{
            outputMetadata.setAudioMimeType(OUTPUT_AUDIO_MIME_TYPE);
        }

    }

    private long getExpectedOutputFileSize(Metadata outputMetadata){
        long totalBitrate = outputMetadata.getCombinedBitrate();
        long duration = outputMetadata.getDuration();
        return (totalBitrate*duration)/8;
    }

    /**
     * This function will create track information list and also discards the tracks other then audio and video
     * @param extractor
     * @return
     */
    private int createTrackInformationList(MediaExtractor extractor) {
        int n = extractor.getTrackCount();
        int noOfTracksToCompress = 0;
        for(int i=0;i<n;i++){
            MediaFormat format = extractor.getTrackFormat(i);
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            if(mimeType.startsWith("video") && outputMetadata.isVideoIncluded()){
                noOfTracksToCompress++;
                LogMessage(mimeType);
                TrackInformation information = new TrackInformation(i,mimeType,OUTPUT_VIDEO_MIME_TYPE);
                trackInformationList.add(information);
            }else if(mimeType.startsWith("audio") && outputMetadata.isAudioIncluded()){
                noOfTracksToCompress++;
                LogMessage(mimeType);
                TrackInformation information = new TrackInformation(i,mimeType,OUTPUT_AUDIO_MIME_TYPE);
                trackInformationList.add(information);
            }else{
                LogMessage(mimeType);
                //Do not compress other tracks
            }
        }
        return noOfTracksToCompress;
    }


    /**
     * Before processing with the compressor make sure that output Metadata is valid.
     * If not assign the original parameter data to it.
     */
    private void filterOutputMetadata() {
        //Video Bitrate is mandatory
        if(outputMetadata.getVideoBitRate() <= 0){
            outputMetadata.setVideoBitRate(originalMetadata.getVideoBitRate());
        }
        //Audio Bitrate is mandatory
        if(outputMetadata.getAudioBitrate() <= 0){
            outputMetadata.setAudioBitrate(originalMetadata.getAudioBitrate());
        }
        //Video FrameRate is mandatory
        if(outputMetadata.getFrameRate() <= 0){
            outputMetadata.setFrameRate(originalMetadata.getFrameRate());
        }
        //video Resolution is mandatory
        if(outputMetadata.getResolution() == null){
            outputMetadata.setResolution(originalMetadata.getResolution());
            outputMetadata.getResolution().adjustResolutions();
        }
        outputMetadata.setRotation(originalMetadata.getRotation());
        //Audio Sample rate should be same as original sample rate
        outputMetadata.setSampleRate(originalMetadata.getSampleRate());
        //Audio channel count should be same as original channel count
        outputMetadata.setChannelCount(originalMetadata.getChannelCount());
        outputMetadata.setFileLocation(outputFilePath);
        //Adjusting the output metadata resolution to be multiple of 16.
        outputMetadata.getResolution().adjustResolutions();
    }



    public void destroy(){
        if(executorService!= null){
            executorService.shutdownNow();
        }
        if(muxer != null) {
            muxer.release();
        }
        videoEncoder = null;
    }

    /**
     * sets the parameters of the output video format
     * must be called after {@link #setSource(String)}
     * @param
     */
    private void handleRotationOfMedia(){
        LogMessage(outputMetadata.toString());
        boolean rotated = outputMetadata.isRotated();
        if(rotated){
            LogMessage("VIDEO is rotated");
            outputMetadata.getResolution().rotate();
        }else{
            LogMessage("Video is not rotated");
        }
    }

    /**
     * Sets the raw resource used as the source video.
     */
    private void setSource(String resId) {
        inputFilePath = resId;
    }

    /**
     * Sets the name of the output file based on the other parameters.
     *
     * <p>Must be called after {@link #handleRotationOfMedia()} (int, int)} and {@link #setSource(String)}.
     */
    private void setOutputFile(String outputFile) {
        this.outputFilePath = outputFile;
        File file = new File(outputFilePath);
        if(file.exists()){
            file.delete();
        }
    }

    private static void LogMessage(String msg){
        Log.d(TAG,msg);
    }
}
