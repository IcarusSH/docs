'use strict';

/**
 * Borrow request detail controller.
 *
 * Loads a single borrow request by id (GET /borrow/{id}) and exposes approve / reject
 * actions for the named approver. The notification dropdown only links here — the
 * dropdown no longer contains inline approve/reject buttons so the approver can read
 * the full context (note, document title, requester username) before deciding.
 */
angular.module('docs').controller('BorrowDetail', function($scope, $rootScope, $state, $stateParams, $translate, Restangular) {
  $scope.borrow = null;
  $scope.error = null;
  $scope.busy = false;

  /**
   * Fetch the request.
   */
  function load() {
    Restangular.one('borrow', $stateParams.id).get().then(function(data) {
      $scope.borrow = data;
    }, function(response) {
      $scope.error = response;
    });
  }
  load();

  /**
   * Can the current user act on this request? Only the named approver (and only while
   * the request is still PENDING) gets approve/reject buttons.
   */
  $scope.canDecide = function() {
    return $scope.borrow
        && $scope.borrow.status === 'PENDING'
        && $rootScope.userInfo
        && $scope.borrow.approver_username === $rootScope.userInfo.username;
  };

  /**
   * Approve the request. Reload after success so the page reflects the new status.
   */
  $scope.approve = function() {
    if ($scope.busy) return;
    $scope.busy = true;
    $scope.error = null;
    Restangular.one('borrow/' + $scope.borrow.id + '/approve').post('').then(function() {
      $scope.busy = false;
      load();
    }, function(response) {
      $scope.busy = false;
      $scope.error = response;
    });
  };

  /**
   * Reject the request.
   */
  $scope.reject = function() {
    if ($scope.busy) return;
    $scope.busy = true;
    $scope.error = null;
    Restangular.one('borrow/' + $scope.borrow.id + '/reject').post('').then(function() {
      $scope.busy = false;
      load();
    }, function(response) {
      $scope.busy = false;
      $scope.error = response;
    });
  };
});
