'use strict';

/**
 * Settings monitoring controller.
 */
angular.module('docs').controller('SettingsMonitoring', function($scope, Restangular) {
  Restangular.one('app').get().then(function(data) {
    $scope.app = data;
  });

  // Log filters: minLevel, tag substring, message substring, max rows.
  $scope.logFilter = {
    level: '',
    tag: '',
    message: '',
    limit: 100
  };

  $scope.reloadLogs = function() {
    var params = {
      limit: $scope.logFilter.limit || 100
    };
    if ($scope.logFilter.level) params.level = $scope.logFilter.level;
    if ($scope.logFilter.tag) params.tag = $scope.logFilter.tag;
    if ($scope.logFilter.message) params.message = $scope.logFilter.message;
    Restangular.one('app/log').get(params).then(function(data) {
      $scope.logs = data.logs;
      $scope.logTotal = data.total;
    });
  };
  $scope.reloadLogs();

  // Build a download URL preserving current filters. We use plain window.location
  // navigation instead of Restangular so the browser shows a "Save as..." dialog.
  $scope.logDownloadUrl = function() {
    var qs = 'limit=' + ($scope.logFilter.limit || 1000) + '&format=text';
    if ($scope.logFilter.level) qs += '&level=' + encodeURIComponent($scope.logFilter.level);
    if ($scope.logFilter.tag) qs += '&tag=' + encodeURIComponent($scope.logFilter.tag);
    if ($scope.logFilter.message) qs += '&message=' + encodeURIComponent($scope.logFilter.message);
    return '../api/app/log?' + qs;
  };

  $scope.reindexingStarted = false;
  $scope.startReindexing = function() {
    Restangular.one('app').post('batch/reindex').then(function () {
      $scope.reindexingStarted = true;
    });
  };
});