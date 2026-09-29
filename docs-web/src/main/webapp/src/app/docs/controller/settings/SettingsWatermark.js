'use strict';

/**
 * Settings watermark page controller.
 */
angular.module('docs').controller('SettingsWatermark', function($scope, $rootScope, Restangular, $translate, $timeout) {
  $scope.watermark = { enabled: true };

  // Load current config
  Restangular.one('app/config_watermark').get().then(function(data) {
    $scope.watermark = data;
  });

  $scope.saveResult = undefined;
  $scope.save = function() {
    Restangular.one('app').post('config_watermark', $scope.watermark).then(function() {
      // Propagate the toggle to $rootScope so the viewer reacts without a page reload.
      if ($rootScope.app) {
        $rootScope.app.watermark_enabled = $scope.watermark.enabled;
      }
      $scope.saveResult = $translate.instant('settings.watermark.saved');
      $timeout(function() {
        $scope.saveResult = undefined;
      }, 5000);
    });
  };
});