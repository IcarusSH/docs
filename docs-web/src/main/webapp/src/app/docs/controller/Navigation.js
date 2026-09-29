'use strict';

/**
 * Navigation controller.
 */
angular.module('docs').controller('Navigation', function($scope, $state, $stateParams, $rootScope, User, Restangular) {
  User.userInfo().then(function(data) {
    $rootScope.userInfo = data;
    if (data.anonymous) {
      if($state.current.name !== 'login') {
        $state.go('login', {
          redirectState: $state.current.name,
          redirectParams: JSON.stringify($stateParams),
        }, {
          location: 'replace'
        });
      }
    }
  });

  /**
   * User logout.
   */
  $scope.logout = function($event) {
    User.logout().then(function() {
      User.userInfo(true).then(function(data) {
        $rootScope.userInfo = data;
      });
      $state.go('main');
    });
    $event.preventDefault();
  };

  /**
   * Stop impersonating (when the banner is up). Reloads so the navigation/ACL
   * state reflects the real admin again.
   */
  $scope.stopImpersonating = function($event) {
    if ($event) $event.preventDefault();
    Restangular.one('admin/impersonate/stop').post('').then(function() {
      window.location.reload();
    });
  };
});