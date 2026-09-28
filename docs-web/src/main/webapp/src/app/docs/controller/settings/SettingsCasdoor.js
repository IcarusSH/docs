'use strict';

/**
 * Settings Casdoor page controller.
 */
angular.module('docs').controller('SettingsCasdoor', function($scope, Restangular, $translate, $timeout) {
  $scope.casdoor = {
    enabled: false
  };

  // Get the Casdoor configuration
  Restangular.one('app/config_casdoor').get().then(function(data) {
    $scope.casdoor = data;
    if ($scope.casdoor.default_storage) {
      $scope.casdoor.default_storage /= 1000000;
    }
  });

  // Save Casdoor config
  $scope.saveResult = undefined;
  $scope.save = function() {
    var casdoor = angular.copy($scope.casdoor);
    if (casdoor.default_storage) {
      casdoor.default_storage *= 1000000;
    }
    Restangular.one('app').post('config_casdoor', casdoor).then(function() {
      $scope.saveResult = $translate.instant('settings.casdoor.saved');
      $timeout(function() {
        $scope.saveResult = undefined;
      }, 5000);
    });
  };
});