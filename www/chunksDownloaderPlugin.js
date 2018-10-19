function ChunksDownloaderPlugin() {}

ChunksDownloaderPlugin.prototype.download = function(chunks, filename, successCallback, errorCallback) {
    var options = {};
    options.chunks = chunks;
    options.filename = filename;
    cordova.exec(successCallback, errorCallback, 'ChunksDownloaderPlugin', 'download', [options]);
}

ChunksDownloaderPlugin.install = function() {
    if (!window.plugins) {
    window.plugins = {};
    }
    window.plugins.chunksDownloaderPlugin = new ChunksDownloaderPlugin();
    return window.plugins.chunksDownloaderPlugin;
};
cordova.addConstructor(ChunksDownloaderPlugin.install);
